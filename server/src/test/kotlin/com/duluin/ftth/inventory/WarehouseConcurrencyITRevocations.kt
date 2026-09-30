package com.duluin.ftth.inventory

import com.duluin.ftth.iam.CurrentAuthorityApi
import com.duluin.ftth.iam.application.port.inbound.*
import com.duluin.ftth.iam.application.service.*
import com.duluin.ftth.inventory.adapter.outbound.persistence.WarehouseScopePersistence
import com.duluin.ftth.inventory.application.service.*
import com.duluin.ftth.inventory.domain.model.*
import org.assertj.core.api.Assertions.*
import org.junit.jupiter.api.*
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.security.core.context.SecurityContextHolder
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WarehouseConcurrencyITRevocations {
    private lateinit var database: WarehouseSchemaDatabase
    private lateinit var context: org.springframework.context.ConfigurableApplicationContext
    @BeforeAll fun start() { database=WarehouseSchemaDatabase(); context=postingContext(database) }
    @AfterAll fun stop() { context.close(); database.close() }
    @AfterEach fun clear() { SecurityContextHolder.clearContext() }

    private inner class Setup {
        val fixture=WarehousePostingFixture(context).also { it.setup(); it.legacyAuthority() }
        val admin=UUID.randomUUID()
        val area=UUID.randomUUID()
        val role=UUID.randomUUID()
        val command: WarehousePreparedCommand
        val origin: UUID
        init {
            fixture.authenticateLegacy(admin)
            fixture.transaction {
                sql("INSERT INTO area(id,tenant_id,code,name) VALUES ('$area','$tenant','A','Area')")
                sql("INSERT INTO role(id,tenant_id,name) VALUES ('$role','$tenant','Mover')")
                sql("INSERT INTO role_permission(role_id,permission_id) SELECT '$role',id FROM permission WHERE code IN ('inventory.transfer.manage','workorder.order.field')")
                sql("INSERT INTO user_role(user_id,role_id) VALUES ('$actor','$role')")
                sql("INSERT INTO user_area(user_id,area_id) VALUES ('$actor','$area')")
                sql("UPDATE inventory_location SET area_id='$area',revision=revision+1 WHERE id IN ('$warehouse','$technician')")
                context.getBean(WarehouseScopePersistence::class.java).replace(actor,setOf(warehouse,technician),0)
                sql("UPDATE app_user SET platform_admin=false WHERE id='$actor'")
            }
            fixture.authenticateLegacy()
            val piece=fixture.transaction { receipt(StockQuantity.each("1")) }
            origin=fixture.transaction { UUID.fromString(scalar("SELECT origin.document_id FROM inventory_serialized_asset asset JOIN inventory_document_line origin ON origin.id=asset.origin_document_line_id WHERE asset.id='${piece.stockIdentityId}'")) }
            command=fixture.transaction { WarehousePreparedCommand.prepare(move(piece,piece.copy(locationId=technician,custodianId=actor,custodianKind=OwnerKind.TECHNICIAN),StockQuantity.each("1")),UUID.randomUUID().toString(),0,mapOf("document:$origin" to 1L)) }
        }
        fun revoke(mode: String) {
            fixture.authenticateLegacy(admin)
            fixture.transaction {
                when(mode) {
                    "DISABLE" -> context.getBean(UserService::class.java).setEnabled(actor,false)
                    "ROLE" -> context.getBean(RoleService::class.java).update(role,UpdateRoleCommand("Mover",null,emptySet()))
                    "AREA" -> context.getBean(UserService::class.java).assignAccess(actor,AssignAccessCommand(setOf(role),emptySet()))
                    "WAREHOUSE" -> context.getBean(WarehouseScopePersistence::class.java).replace(actor,emptySet(),0)
                }
            }
        }
        fun execute() = fixture.transaction { context.getBean(WarehouseCommandService::class.java).execute(command) }
    }

    @ParameterizedTest @ValueSource(strings=["DISABLE","ROLE","AREA","WAREHOUSE"])
    fun `revocation waits for executing command then denies replay with original JWT`(mode: String) {
        val setup=Setup()
        val held=CountDownLatch(1); val release=CountDownLatch(1)
        val pool=Executors.newFixedThreadPool(2)
        try {
            val execute=pool.submit { setup.fixture.authenticateLegacy(); setup.fixture.transaction {
                val receipt=context.getBean(WarehouseCommandService::class.java).execute(setup.command)
                held.countDown(); check(release.await(20,TimeUnit.SECONDS)); receipt
            } }
            check(held.await(15,TimeUnit.SECONDS))
            val revoke=pool.submit { setup.revoke(mode) }
            val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10)
            var blocked=false
            while(!blocked && System.nanoTime()<deadline) {
                blocked=setup.fixture.transaction { scalar("SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND usename=current_user AND wait_event_type='Lock' AND query LIKE '%iam_authorization_epoch%'").toInt()>0 }
                Thread.yield()
            }
            assertThat(blocked).isTrue(); release.countDown(); execute.get(20,TimeUnit.SECONDS); revoke.get(20,TimeUnit.SECONDS)
            setup.fixture.authenticateLegacy()
            val before=setup.fixture.transaction { counts() }
            assertThatThrownBy { setup.execute() }.isInstanceOfAny(WarehouseContractException::class.java,com.duluin.ftth.common.domain.error.AccessDeniedException::class.java)
            assertThat(setup.fixture.transaction { counts() }).isEqualTo(before)
        } finally { release.countDown(); pool.shutdownNow(); pool.awaitTermination(10,TimeUnit.SECONDS) }
    }

    @ParameterizedTest @ValueSource(strings=["DISABLE","ROLE","AREA","WAREHOUSE"])
    fun `minted snapshot cannot execute after committed authority removal`(mode: String) {
        val setup=Setup()
        val epoch=setup.fixture.transaction { context.getBean(CurrentAuthorityApi::class.java).lockCurrent().fence.epoch }
        setup.revoke(mode); setup.fixture.authenticateLegacy()
        val minted=WarehousePreparedCommand.prepare(setup.command.posting,setup.command.key,0,setup.command.referencedRevisions,epoch)
        val before=setup.fixture.transaction { counts() }
        assertThatThrownBy { setup.fixture.transaction { context.getBean(WarehouseCommandService::class.java).execute(minted) } }
            .isInstanceOfAny(WarehouseContractException::class.java,com.duluin.ftth.common.domain.error.AccessDeniedException::class.java)
        assertThat(setup.fixture.transaction { counts() }).isEqualTo(before)
    }

    @Test fun `referenced document is revalidated on first execution and replay`() {
        listOf(false,true).forEach { replay ->
            val setup=Setup()
            if(replay) setup.execute()
            setup.fixture.transaction { sql("UPDATE inventory_document SET state='PUTAWAY',revision=revision+1 WHERE id='${setup.origin}'") }
            val before=setup.fixture.transaction { counts() }
            assertThatThrownBy { setup.execute() }.hasMessageContaining("STALE_REVISION")
            assertThat(setup.fixture.transaction { counts() }).isEqualTo(before)
        }
    }

    @Test fun `second change invalidates minted in-transaction snapshot without double epoch increment`() {
        val setup=Setup()
        setup.fixture.authenticateLegacy(setup.admin)
        setup.fixture.transaction {
            val api=context.getBean(CurrentAuthorityApi::class.java)
            val change=api.lockForChange(); val epoch=change.incrementEpoch(); val snapshot=api.lockCurrent()
            assertThat(change.incrementEpoch()).isEqualTo(epoch)
            assertThatThrownBy { snapshot.fence.assertHeld() }.isInstanceOf(IllegalStateException::class.java)
        }
    }
}

package com.duluin.ftth.inventory

import com.duluin.ftth.iam.application.port.outbound.PermissionRepository
import com.duluin.ftth.inventory.application.service.*
import com.duluin.ftth.inventory.adapter.outbound.persistence.WarehouseScopePersistence
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
class WarehouseConcurrencyITReferences {
    private lateinit var database: WarehouseSchemaDatabase
    private lateinit var context: org.springframework.context.ConfigurableApplicationContext
    @BeforeAll fun start() { database=WarehouseSchemaDatabase(); context=postingContext(database) }
    @AfterAll fun stop() { context.close(); database.close() }
    @AfterEach fun clear() { SecurityContextHolder.clearContext() }

    private fun command(fixture: WarehousePostingFixture): InventoryFulfillmentCommand {
        val piece=fixture.transaction { acknowledge(receipt(StockQuantity.each("1"))) }
        return InventoryFulfillmentCommand(fixture.tenant,piece.stockIdentityId,piece.stockIdentityId,piece.skuId,piece.locationId,
            fixture.customer,fixture.workOrder,1,true,true,fixture.actor,"ignored","use-${UUID.randomUUID()}","ignored","Use","ONU")
    }

    private fun waitForLock(fixture: WarehousePostingFixture, query: String) {
        val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(15)
        var blocked=false
        while(!blocked && System.nanoTime()<deadline) {
            blocked=fixture.transaction { scalar("SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND usename=current_user AND wait_event_type='Lock' AND query LIKE '%$query%'").toInt()>0 }
            Thread.yield()
        }
        assertThat(blocked).isTrue()
    }

    @ParameterizedTest @ValueSource(strings=["work_order","inventory_document"])
    fun `bounded reference lock failures map to conflict instead of server error`(table: String) {
        val fixture=WarehousePostingFixture(context).also { it.setup(); it.legacyAuthority() }
        val piece=fixture.transaction { receipt(StockQuantity.each("1")) }
        val post=fixture.transaction { move(piece,piece.copy(locationId=technician,custodianId=actor,custodianKind=OwnerKind.TECHNICIAN),StockQuantity.each("1")) }
        val command=WarehousePreparedCommand.prepare(post,"lock-timeout",0,mapOf("workorder:${fixture.workOrder}" to 1L))
        val id=if(table=="work_order") fixture.workOrder else post.documentId
        val held=CountDownLatch(1); val release=CountDownLatch(1); val pool=Executors.newSingleThreadExecutor()
        try {
            val writer=pool.submit { fixture.transaction {
                scalar("SELECT id FROM $table WHERE id='$id' FOR UPDATE")
                held.countDown(); check(release.await(10,TimeUnit.SECONDS))
            } }
            check(held.await(10,TimeUnit.SECONDS))
            assertThatThrownBy { fixture.transaction {
                sql("SET LOCAL lock_timeout='100ms'")
                context.getBean(WarehouseCommandService::class.java).execute(command)
            } }.isInstanceOfSatisfying(WarehouseContractException::class.java) { assertThat(it.error.code).isEqualTo(WarehouseErrorCode.STALE_REVISION) }
            release.countDown(); writer.get(10,TimeUnit.SECONDS)
        } finally { release.countDown(); pool.shutdownNow(); pool.awaitTermination(10,TimeUnit.SECONDS) }
    }

    @Test fun `actual WO reassignment waits for consume and revokes replay`() {
        val fixture=WarehousePostingFixture(context).also { it.setup(); it.legacyAuthority() }
        val command=command(fixture)
        val other=UUID.randomUUID(); fixture.authenticateLegacy(other); fixture.authenticateLegacy()
        fixture.transaction {
            val role=context.getBean(com.duluin.ftth.iam.application.service.AdminProvisioner::class.java).ensureTechnicianRole(tenant)
            context.getBean(com.duluin.ftth.iam.application.service.UserService::class.java).assignAccess(other,
                com.duluin.ftth.iam.application.port.inbound.AssignAccessCommand(setOf(role),emptySet()))
        }
        val held=CountDownLatch(1); val release=CountDownLatch(1); val pool=Executors.newFixedThreadPool(2)
        try {
            val consume=pool.submit { fixture.authenticateLegacy(); fixture.transaction {
                context.getBean(WarehouseCommandService::class.java).executeLegacy(command,false)
                held.countDown(); check(release.await(25,TimeUnit.SECONDS))
            } }
            check(held.await(15,TimeUnit.SECONDS))
            val reassign=pool.submit { fixture.authenticateLegacy(other); fixture.transaction {
                context.getBean(com.duluin.ftth.workorder.application.service.WorkOrderService::class.java).assign(workOrder,setOf(other))
            } }
            waitForLock(fixture,"work_order"); release.countDown(); consume.get(25,TimeUnit.SECONDS); reassign.get(25,TimeUnit.SECONDS)
            fixture.authenticateLegacy()
            val before=fixture.transaction { counts() }
            assertThatThrownBy { fixture.transaction { context.getBean(WarehouseCommandService::class.java).executeLegacy(command,false) } }.hasMessageContaining("FORBIDDEN")
            assertThat(fixture.transaction { counts() }).isEqualTo(before)
        } finally { release.countDown(); pool.shutdownNow(); pool.awaitTermination(10,TimeUnit.SECONDS) }
    }

    @ParameterizedTest @ValueSource(booleans=[false,true])
    fun `referenced document writer blocks first execution and replay then invalidates snapshot`(replay: Boolean) {
        val fixture=WarehousePostingFixture(context).also { it.setup(); it.legacyAuthority() }
        val piece=fixture.transaction { receipt(StockQuantity.each("1")) }
        val origin=fixture.transaction { UUID.fromString(scalar("SELECT line.document_id FROM inventory_serialized_asset asset JOIN inventory_document_line line ON line.id=asset.origin_document_line_id WHERE asset.id='${piece.stockIdentityId}'")) }
        val command=fixture.transaction { WarehousePreparedCommand.prepare(move(piece,piece.copy(locationId=technician,custodianId=actor,custodianKind=OwnerKind.TECHNICIAN),StockQuantity.each("1")),"reference",0,mapOf("document:$origin" to 1L)) }
        if(replay) fixture.transaction { context.getBean(WarehouseCommandService::class.java).execute(command) }
        val held=CountDownLatch(1); val release=CountDownLatch(1); val pool=Executors.newFixedThreadPool(2)
        try {
            val change=pool.submit { fixture.transaction {
                context.getBean(InventoryTenantCutoverApi::class.java).lockForCommand(0,WarehouseOperationClass.ORDINARY_STOCK)
                sql("UPDATE inventory_document SET state='PUTAWAY',revision=revision+1 WHERE id='$origin'")
                held.countDown(); check(release.await(25,TimeUnit.SECONDS))
            } }
            check(held.await(15,TimeUnit.SECONDS))
            val execute=pool.submit<Throwable?> { fixture.authenticateLegacy(); catchThrowable {
                fixture.transaction { context.getBean(WarehouseCommandService::class.java).execute(command) }
            } }
            waitForLock(fixture,"inventory_document"); release.countDown(); change.get(25,TimeUnit.SECONDS)
            assertThat(execute.get(25,TimeUnit.SECONDS)).isInstanceOf(WarehouseContractException::class.java).hasMessageContaining("STALE_REVISION")
        } finally { release.countDown(); pool.shutdownNow(); pool.awaitTermination(10,TimeUnit.SECONDS) }
    }

    @Test fun `global catalog revocation waits for command fence and invalidates original JWT`() {
        val fixture=WarehousePostingFixture(context).also { it.setup(); it.legacyAuthority() }
        fixture.transaction {
            val role=UUID.randomUUID()
            sql("INSERT INTO role(id,tenant_id,name) VALUES ('$role','$tenant','Field')")
            sql("INSERT INTO role_permission(role_id,permission_id) SELECT '$role',id FROM permission WHERE code='workorder.order.field'")
            sql("INSERT INTO user_role(user_id,role_id) VALUES ('$actor','$role')")
            context.getBean(WarehouseScopePersistence::class.java).replace(actor,setOf(warehouse,technician),0)
            sql("UPDATE app_user SET platform_admin=false WHERE id='$actor'")
        }
        val command=command(fixture)
        val held=CountDownLatch(1); val release=CountDownLatch(1); val pool=Executors.newFixedThreadPool(2)
        try {
            val execute=pool.submit { fixture.authenticateLegacy(); fixture.transaction {
                context.getBean(WarehouseCommandService::class.java).executeLegacy(command,false)
                held.countDown(); check(release.await(25,TimeUnit.SECONDS))
            } }
            check(held.await(15,TimeUnit.SECONDS))
            val change=pool.submit { fixture.transaction {
                val permissions=context.getBean(PermissionRepository::class.java)
                val field=permissions.findAll().first { it.code.value=="workorder.order.field" }
                field.deactivate(); permissions.save(field)
            } }
            waitForLock(fixture,"pg_advisory_xact_lock("); release.countDown(); execute.get(25,TimeUnit.SECONDS); change.get(25,TimeUnit.SECONDS)
            fixture.authenticateLegacy()
            assertThatThrownBy { fixture.transaction { context.getBean(WarehouseCommandService::class.java).executeLegacy(command,false) } }.hasMessageContaining("FORBIDDEN")
        } finally { release.countDown(); pool.shutdownNow(); pool.awaitTermination(10,TimeUnit.SECONDS) }
    }
}

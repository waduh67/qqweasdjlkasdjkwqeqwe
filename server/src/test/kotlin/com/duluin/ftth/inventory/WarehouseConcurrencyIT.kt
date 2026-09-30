package com.duluin.ftth.inventory

import com.duluin.ftth.common.infrastructure.security.JwtAuthenticationConverter
import com.duluin.ftth.iam.application.service.UserService
import com.duluin.ftth.inventory.application.service.*
import com.duluin.ftth.inventory.domain.model.*
import org.assertj.core.api.Assertions.*
import org.junit.jupiter.api.*
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.jwt.Jwt
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WarehouseConcurrencyIT {
    private lateinit var database: WarehouseSchemaDatabase
    private lateinit var context: org.springframework.context.ConfigurableApplicationContext
    @BeforeAll fun start() { database = WarehouseSchemaDatabase(); context = postingContext(database) }
    @AfterAll fun stop() { context.close(); database.close() }
    @AfterEach fun clear() { SecurityContextHolder.clearContext() }

    private fun fixture() = WarehousePostingFixture(context).also { fixture ->
        fixture.setup()
        fixture.transaction { sql("INSERT INTO app_user(id,tenant_id,email,name,password_hash,platform_admin) VALUES ('$actor','$tenant','$actor@example.test','Actor','unused',true)") }
        authenticate(fixture)
    }
    private fun authenticate(fixture: WarehousePostingFixture, user: UUID = fixture.actor, session: String = "original") {
        SecurityContextHolder.getContext().authentication = JwtAuthenticationConverter().convert(
            Jwt.withTokenValue(session).header("alg", "RS256").subject(user.toString()).claim("tid", fixture.tenant.toString())
                .claim("email", "$user@example.test").claim("name", "Actor").claim("padm", true)
                .claim("perms", listOf("inventory.transfer.manage")).jti(session).build())
    }
    private fun command(fixture: WarehousePostingFixture): WarehousePreparedCommand {
        val piece = fixture.transaction { receipt(StockQuantity.each("1")) }
        val post = fixture.transaction { move(piece, piece.copy(locationId = technician, custodianId = actor, custodianKind = OwnerKind.TECHNICIAN), StockQuantity.each("1")) }
        return WarehousePreparedCommand.prepare(post, UUID.randomUUID().toString(), 0, emptyMap())
    }
    private fun execute(fixture: WarehousePostingFixture, command: WarehousePreparedCommand): WarehouseOperationReceipt =
        fixture.transaction { context.getBean(WarehouseCommandService::class.java).execute(command) }

    @Test fun `lost response and fresh session replay exact original status body and timestamp`() {
        val fixture = fixture()
        val command = command(fixture)
        val original = execute(fixture, command)
        val counts = fixture.transaction { counts() }
        authenticate(fixture, session = "fresh-authorized-session")
        assertThat(execute(fixture, command)).isEqualTo(original)
        assertThat(fixture.transaction { counts() }).isEqualTo(counts)
        fixture.transaction {
            assertThat(scalar("SELECT count(*) FROM inventory_operation WHERE namespace='${command.namespace}'")).isEqualTo("1")
            assertThat(scalar("SELECT original_session_id FROM inventory_command_identity WHERE id='${original.operationId}'")).isEqualTo("original")
            assertThat(total(technician, StockUnit.EA)).isEqualTo(1)
        }
    }

    @Test fun `changed payload and different key same action revision conflict without posting`() {
        val fixture = fixture()
        val command = command(fixture)
        execute(fixture, command)
        val before = fixture.transaction { counts() }
        val changed = WarehousePreparedCommand.prepare(command.posting.copy(reason = "Changed"), command.key, 0, emptyMap())
        val rekeyed = WarehousePreparedCommand.prepare(command.posting, "different-key", 0, emptyMap())
        listOf(changed, rekeyed).forEach { request ->
            assertThatThrownBy { execute(fixture, request) }.isInstanceOf(WarehouseContractException::class.java)
                .hasMessageContaining("IDEMPOTENCY_CONFLICT")
        }
        assertThat(fixture.transaction { counts() }).isEqualTo(before)
    }

    @Test fun `different actor and disabled actor cannot retrieve stored payload with original JWT`() {
        val fixture = fixture()
        val command = command(fixture)
        val original = execute(fixture, command)
        val other = UUID.randomUUID()
        fixture.transaction { sql("INSERT INTO app_user(id,tenant_id,email,name,password_hash,platform_admin) VALUES ('$other','$tenant','$other@example.test','Other','unused',true)") }
        authenticate(fixture, other)
        assertThatThrownBy { execute(fixture, command) }.hasMessageContaining("FORBIDDEN").hasMessageNotContaining(original.originalBody)
        fixture.transaction { context.getBean(UserService::class.java).setEnabled(actor, false) }
        authenticate(fixture)
        assertThatThrownBy { execute(fixture, command) }.isInstanceOf(com.duluin.ftth.common.domain.error.AccessDeniedException::class.java)
        val firstExecution = command(fixture)
        assertThatThrownBy { execute(fixture, firstExecution) }.isInstanceOf(com.duluin.ftth.common.domain.error.AccessDeniedException::class.java)
    }

    @Test fun `last asset competing commands produce one business posting`() {
        val fixture = fixture()
        val first = command(fixture)
        val second = fixture.transaction {
            val source = first.posting.legs.first().dimension
            WarehousePreparedCommand.prepare(move(source, first.posting.legs.last().dimension, StockQuantity.each("1")), "competing", 0, emptyMap())
        }
        val pool = Executors.newFixedThreadPool(2)
        val start = CountDownLatch(1)
        try {
            val futures = listOf(first, second).map { request -> pool.submit<Boolean> {
                authenticate(fixture); check(start.await(10, TimeUnit.SECONDS))
                try { execute(fixture, request); true } catch (failure: WarehouseContractException) { false }
            } }
            start.countDown()
            assertThat(futures.map { it.get(20, TimeUnit.SECONDS) }.count { it }).isEqualTo(1)
            fixture.transaction { assertThat(total(technician, StockUnit.EA)).isEqualTo(1) }
        } finally { pool.shutdownNow(); pool.awaitTermination(10, TimeUnit.SECONDS) }
    }

    @Test fun `canonical property order agrees while malformed and ambiguous payload fails`() {
        assertThat(WarehouseCanonicalPayload.parse("{\"b\":2,\"a\":{\"z\":0,\"x\":1}}").hash)
            .isEqualTo(WarehouseCanonicalPayload.parse("{\"a\":{\"x\":1,\"z\":0},\"b\":2}").hash)
        listOf("{", "[]", "{\"a\":1,\"a\":2}", "{\"qty\":1.5}", "{} {}").forEach { raw ->
            assertThatThrownBy { WarehouseCanonicalPayload.parse(raw) }.isInstanceOf(WarehouseContractException::class.java)
        }
    }

    @Test fun `warehouse scope revocation denies both replay and new command despite original JWT`() {
        val fixture = fixture()
        val command = command(fixture)
        val scopes = context.getBean(com.duluin.ftth.inventory.adapter.outbound.persistence.WarehouseScopePersistence::class.java)
        fixture.transaction {
            val permission = UUID.fromString(scalar("SELECT id FROM permission WHERE code='inventory.transfer.manage'"))
            val role = context.getBean(com.duluin.ftth.iam.application.service.RoleService::class.java)
                .create(com.duluin.ftth.iam.application.port.inbound.CreateRoleCommand("Mover", null, setOf(permission)))
            context.getBean(UserService::class.java).assignAccess(actor,
                com.duluin.ftth.iam.application.port.inbound.AssignAccessCommand(setOf(role.id), emptySet()))
            scopes.replace(actor, setOf(warehouse, technician), 0)
            sql("UPDATE app_user SET platform_admin=false WHERE id='$actor'")
        }
        execute(fixture, command)
        val next = command(fixture)
        fixture.transaction {
            context.getBean(com.duluin.ftth.iam.CurrentAuthorityApi::class.java).lockForChange().incrementEpoch()
            sql("UPDATE inventory_warehouse_scope SET state='REVOKED',revision=revision+1 WHERE user_id='$actor'")
        }
        listOf(command, next).forEach { request ->
            assertThatThrownBy { execute(fixture, request) }.hasMessageContaining("FORBIDDEN")
        }
    }

    @Test fun `stale authority and cutover epochs reject command before any posting`() {
        val fixture = fixture()
        val command = command(fixture)
        val counts = fixture.transaction { counts() }
        val staleAuthority = WarehousePreparedCommand.prepare(command.posting, command.key, 0, emptyMap(), Long.MAX_VALUE)
        val staleCutover = WarehousePreparedCommand.prepare(command.posting, command.key, 1, emptyMap())
        assertThatThrownBy { execute(fixture, staleAuthority) }.hasMessageContaining("STALE_AUTHORITY")
        assertThatThrownBy { execute(fixture, staleCutover) }.isInstanceOfSatisfying(WarehouseContractException::class.java) {
            assertThat(it.error.code).isEqualTo(WarehouseErrorCode.STALE_CUTOVER)
        }
        assertThat(fixture.transaction { counts() }).isEqualTo(counts)
    }
}

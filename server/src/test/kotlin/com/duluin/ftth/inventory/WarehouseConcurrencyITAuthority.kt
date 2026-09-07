package com.duluin.ftth.inventory

import com.duluin.ftth.common.infrastructure.security.JwtAuthenticationConverter
import com.duluin.ftth.iam.CurrentAuthorityApi
import com.duluin.ftth.iam.application.service.UserService
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.*
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.jwt.Jwt
import java.util.UUID
import com.duluin.ftth.iam.application.port.inbound.*
import com.duluin.ftth.iam.application.service.RoleService
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WarehouseConcurrencyITAuthority {
    private lateinit var database: WarehouseSchemaDatabase
    private lateinit var context: org.springframework.context.ConfigurableApplicationContext
    @BeforeAll fun start() { database = WarehouseSchemaDatabase(); context = postingContext(database) }
    @AfterAll fun stop() { context.close(); database.close() }
    @AfterEach fun clearIdentity() { SecurityContextHolder.clearContext() }

    private fun fixture(): WarehousePostingFixture = WarehousePostingFixture(context).also { fixture ->
        fixture.transaction {
            sql("INSERT INTO app_user(id,tenant_id,email,name,password_hash) VALUES ('$actor','$tenant','$actor@example.test','Warehouse actor','unused')")
        }
    }

    private fun authenticate(fixture: WarehousePostingFixture, actor: UUID = fixture.actor) {
        val jwt = Jwt.withTokenValue("original-signed-session").header("alg", "RS256")
            .subject(actor.toString()).claim("tid", fixture.tenant.toString())
            .claim("email", "$actor@example.test").claim("name", "Actor")
            .claim("perms", listOf("inventory.transfer.manage")).claim("padm", true).build()
        SecurityContextHolder.getContext().authentication = JwtAuthenticationConverter().convert(jwt)
    }

    @Test fun `disable increments epoch once and failed mutation rolls epoch back`() {
        val fixture = fixture()
        authenticate(fixture, UUID.randomUUID())
        val users = context.getBean(UserService::class.java)
        val before = fixture.transaction { scalar("SELECT epoch FROM iam_authorization_epoch").toLong() }
        fixture.transaction { users.setEnabled(actor, false); users.setEnabled(actor, true) }
        assertThat(fixture.transaction { scalar("SELECT epoch FROM iam_authorization_epoch").toLong() }).isEqualTo(before + 1)
        assertThatThrownBy { fixture.transaction { users.setEnabled(actor, false); error("rollback") } }.isInstanceOf(IllegalStateException::class.java)
        assertThat(fixture.transaction { scalar("SELECT epoch FROM iam_authorization_epoch").toLong() }).isEqualTo(before + 1)
        assertThat(fixture.transaction { scalar("SELECT status FROM app_user WHERE id='$actor'") }).isEqualTo("ACTIVE")
    }

    @Test fun `current authority ignores original JWT privileges and rejects disabled user`() {
        val fixture = fixture()
        authenticate(fixture)
        val authorities = context.getBean(CurrentAuthorityApi::class.java)
        val authority = fixture.transaction { authorities.lockCurrent().also { it.fence.assertHeld() } }
        assertThat(authority.permissions).isEmpty()
        assertThat(authority.platformAdmin).isFalse()
        assertThatThrownBy { authority.fence.assertHeld() }.isInstanceOf(IllegalStateException::class.java)
        authenticate(fixture, UUID.randomUUID())
        fixture.transaction { context.getBean(UserService::class.java).setEnabled(actor, false) }
        authenticate(fixture)
        assertThatThrownBy { fixture.transaction { authorities.lockCurrent() } }
            .isInstanceOf(com.duluin.ftth.common.domain.error.AccessDeniedException::class.java)
    }

    @Test fun `role revocation waits for shared authority and original JWT loses permission`() {
        val fixture = fixture()
        authenticate(fixture)
        val roles = context.getBean(RoleService::class.java)
        val authority = context.getBean(CurrentAuthorityApi::class.java)
        val role = fixture.transaction {
            val permission = UUID.fromString(scalar("SELECT id FROM permission WHERE code='inventory.transfer.manage'"))
            val created = roles.create(CreateRoleCommand("Warehouse", null, setOf(permission)))
            context.getBean(UserService::class.java).assignAccess(actor, AssignAccessCommand(setOf(created.id), emptySet()))
            created
        }
        val held = CountDownLatch(1)
        val release = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val reader = pool.submit { authenticate(fixture); fixture.transaction {
                val snapshot = authority.lockCurrent()
                assertThat(snapshot.permissions).contains("inventory.transfer.manage")
                held.countDown()
                check(release.await(15, TimeUnit.SECONDS))
                snapshot.fence.assertHeld()
            } }
            check(held.await(10, TimeUnit.SECONDS))
            val revoke = pool.submit { authenticate(fixture); fixture.transaction {
                roles.update(role.id, UpdateRoleCommand("Warehouse", null, emptySet()))
            } }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            var blocked = false
            while (!blocked && System.nanoTime() < deadline) {
                blocked = fixture.transaction { scalar("SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND usename=current_user AND wait_event_type='Lock' AND query LIKE '%iam_authorization_epoch%'").toInt() > 0 }
                Thread.yield()
            }
            assertThat(blocked).isTrue()
            release.countDown()
            reader.get(15, TimeUnit.SECONDS); revoke.get(15, TimeUnit.SECONDS)
            assertThat(fixture.transaction { authority.lockCurrent().permissions }).doesNotContain("inventory.transfer.manage")
        } finally { release.countDown(); pool.shutdownNow(); pool.awaitTermination(10, TimeUnit.SECONDS) }
    }
}

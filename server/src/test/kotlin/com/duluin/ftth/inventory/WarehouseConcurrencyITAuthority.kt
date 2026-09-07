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
}

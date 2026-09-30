package com.duluin.ftth.portal

import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.inventory.WarehouseMasterHttpFixture
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID

class PortalContactIdentityIT : WarehouseMasterHttpFixture() {
    @Autowired private lateinit var transactions: PlatformTransactionManager

    @Test fun `committed contact edits replace portal identities while rollback and other tenants preserve their identities`() {
        val admin = tenant()
        val otherAdmin = tenant()
        val suffix = UUID.randomUUID().toString().take(8)
        val password = "portal-contact-test-only"
        val login = "contact-$suffix"
        val oldEmail = "before-$suffix@example.test"
        val newEmail = "after-$suffix@example.test"
        val phonePrefix = "+6281" + UUID.randomUUID().toString().filter(Char::isDigit).take(8).padStart(8, '0')
        val oldPhone = phonePrefix + "1"
        val newPhone = phonePrefix + "2"
        fun customerBody(token: String, email: String, phone: String) = mapper.writeValueAsString(mapOf(
            "name" to "Portal contact regression", "address" to "Test address", "email" to email, "phone" to phone,
            "areaId" to area(token), "location" to mapOf("longitude" to 106.82, "latitude" to -6.18)))
        fun createCustomer(token: String, email: String, phone: String, username: String): String {
            val created = request("POST", "/api/customers", token, customerBody(token, email, phone))
            assertThat(created.status).isEqualTo(201)
            val id = mapper.readTree(created.contentAsString).path("id").asString()
            assertThat(request("POST", "/api/portal-admin/customers/$id/credential", token,
                mapper.writeValueAsString(mapOf("login" to username, "password" to password))).status).isEqualTo(200)
            return id
        }
        fun authenticate(identifier: String) = request("POST", "/api/portal/auth/login", null,
            mapper.writeValueAsString(mapOf("identifier" to identifier, "password" to password))).status
        val customer = createCustomer(admin, oldEmail, oldPhone, login)
        val otherEmail = "other-$suffix@example.test"
        createCustomer(otherAdmin, otherEmail, phonePrefix + "3", "other-$login")
        assertThat(authenticate(oldEmail)).isEqualTo(200)
        assertThat(authenticate(oldPhone)).isEqualTo(200)
        assertThat(request("PUT", "/api/customers/$customer", admin, customerBody(admin, newEmail, newPhone)).status).isEqualTo(200)
        assertThat(authenticate(newEmail)).describedAs("Committed email must be indexed after the customer transaction closes").isEqualTo(200)
        assertThat(authenticate(newPhone)).isEqualTo(200)
        assertThat(authenticate(login)).isEqualTo(200)
        assertThat(authenticate(oldEmail)).isEqualTo(401)
        assertThat(authenticate(oldPhone)).isEqualTo(401)
        assertThat(authenticate(otherEmail)).isEqualTo(200)

        val tenantId = UUID.fromString(mapper.readTree(request("GET", "/api/me", admin).contentAsString).path("tenantId").asString())
        val rolledBackEmail = "rollback-$suffix@example.test"
        TenantContext.runAs(tenantId) {
            TransactionTemplate(transactions).executeWithoutResult { transaction ->
                assertThat(request("PUT", "/api/customers/$customer", admin,
                    customerBody(admin, rolledBackEmail, phonePrefix + "4")).status).isEqualTo(200)
                transaction.setRollbackOnly()
            }
        }
        assertThat(authenticate(rolledBackEmail)).isEqualTo(401)
        assertThat(authenticate(newEmail)).isEqualTo(200)
        assertThat(authenticate(newPhone)).isEqualTo(200)
        assertThat(authenticate(otherEmail)).isEqualTo(200)
    }
}

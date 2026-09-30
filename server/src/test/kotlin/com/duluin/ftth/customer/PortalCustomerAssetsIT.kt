package com.duluin.ftth.customer

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class PortalCustomerAssetsIT : CustomerAssetOwnershipFixture() {
    private fun portal(customer: UUID, admin: String): String {
        val login = "asset-${UUID.randomUUID()}"
        val credential = request("POST", "/api/portal-admin/customers/$customer/credential", admin,
            """{"login":"$login","password":"local-portal-test-only"}""")
        assertThat(credential.status).withFailMessage(credential.contentAsString).isEqualTo(200)
        val response = request("POST", "/api/portal/auth/login", null,
            """{"identifier":"$login","password":"local-portal-test-only"}""")
        assertThat(response.status).withFailMessage(response.contentAsString).isEqualTo(200)
        return mapper.readTree(response.contentAsString).path("tokens").path("accessToken").asString().also { assertThat(it).isNotBlank() }
    }
    @Test fun `portal assets are principal only with safe title projection and no operational identifiers`() {
        val sale = ownershipCase("SALE")
        assertThat(accept(sale).status).isEqualTo(200)
        val install = sale.installation
        val token = portal(install.customer, install.receipt.stock.token)
        val path = "/api/portal/me/assets"
        val response = request("GET", "$path?size=1", token)
        assertThat(response.status).withFailMessage(response.contentAsString).isEqualTo(200)
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store")
        val body = mapper.readTree(response.contentAsString)
        assertThat(body.path("totalElements").asLong()).isEqualTo(1)
        val asset = body.path("items").single()
        assertThat(asset.propertyNames()).containsExactlyInAnyOrder("deviceLabel", "serialNumber", "ownershipMode", "legalOwner", "provenance", "installedAt", "removedAt")
        assertThat(asset.path("serialNumber").asString()).isEqualTo(install.receipt.input.lines.single().serial)
        assertThat(asset.path("legalOwner").asString()).isEqualTo("CUSTOMER")
        assertThat(response.contentAsString).doesNotContain(install.customer.toString(), install.operation.toString(), install.receipt.workOrder, "cost", "stock", "evidence", "actor")
        for (suffix in listOf("?customerId=${UUID.randomUUID()}", "?assetId=${install.receipt.input.lines.single().stockIdentityId}", "?page=0&page=1", "?size=101"))
            assertThat(request("GET", "$path$suffix", token).status).isEqualTo(400)
        assertThat(request("GET", path, install.receipt.stock.token).status).isEqualTo(401)
        assertThat(request("GET", path, null).status).isEqualTo(401)
        val newCustomer = request("POST", "/api/customers", install.receipt.stock.token,
            """{"code":"C-${UUID.randomUUID()}","name":"Other customer","address":"Test","location":{"longitude":106.9,"latitude":-6.2},"areaId":"${area(install.receipt.stock.token)}"}""")
        assertThat(newCustomer.status).withFailMessage(newCustomer.contentAsString).isEqualTo(201)
        val other = portal(UUID.fromString(mapper.readTree(newCustomer.contentAsString).path("id").asString()), install.receipt.stock.token)
        val empty = request("GET", path, other)
        assertThat(empty.status).withFailMessage(empty.contentAsString).isEqualTo(200)
        assertThat(mapper.readTree(empty.contentAsString).path("totalElements").asLong()).isZero()
        val foreign = ownershipCase("LOAN", listOf("PORTAL-OTHER-1", "PORTAL-OTHER-2"))
        val foreignResponse = request("GET", path, portal(foreign.installation.customer, foreign.installation.receipt.stock.token))
        assertThat(foreignResponse.status).withFailMessage(foreignResponse.contentAsString).isEqualTo(200)
        assertThat(foreignResponse.contentAsString).contains("PORTAL-OTHER-1").doesNotContain(install.receipt.input.lines.single().serial!!)
    }
}

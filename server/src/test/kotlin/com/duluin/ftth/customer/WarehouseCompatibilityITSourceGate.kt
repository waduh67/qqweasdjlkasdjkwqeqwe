package com.duluin.ftth.customer

import com.duluin.ftth.inventory.WarehouseContractException
import com.duluin.ftth.inventory.WarehouseErrorCode
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class WarehouseCompatibilityITSourceGate : CustomerDeploymentFixture() {
    @Test
    fun `CustomerApi null deployment cannot register a new serial but preserves same customer legacy lookup`() {
        val installation = installation()
        val api = context.getBean(CustomerApi::class.java)
        val error = assertThrows<WarehouseContractException> {
            authenticated(installation) { api.provisionOnu(ProvisionOnuCommand("RAW-API", null, installation.customer, null, null, null)) }
        }
        assertThat(error.error.code).isEqualTo(WarehouseErrorCode.USE_WORKORDER_ASSET_WORKFLOW)
        assertUninstalled(installation)
        val legacy = LegacyOnuTestFixture.stage(installation.customer.toString(), "LEGACY-API")
        val read = authenticated(installation) { api.provisionOnu(ProvisionOnuCommand("LEGACY-API", null, installation.customer, null, null, null)) }
        assertThat(read.id.toString()).isEqualTo(legacy)
        assertThat(read.customerId).isEqualTo(installation.customer)
        assertUninstalled(installation)
    }

    @Test
    fun `readonly and foreign callers cannot assign through customer routes`() {
        val installation = installation()
        val readonly = user(installation.receipt.stock.token, setOf("customer.customer.view", "customer.onu.view"))
        val route = "/api/customers/${installation.customer}/onus"
        assertThat(request("POST", route, readonly.first, """{"serialNumber":"RAW"}""").status).isEqualTo(403)
        assertThat(request("GET", route, tenant()).status).isEqualTo(404)
        val manual = request("POST", route, installation.receipt.stock.token,
            """{"serialNumber":"RAW","assetId":"${installation.receipt.input.lines.single().stockIdentityId}"}""")
        assertThat(manual.status).isIn(400, 409)
        assertUninstalled(installation)
    }
}

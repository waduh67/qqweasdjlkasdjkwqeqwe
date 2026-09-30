package com.duluin.ftth.customer

import com.duluin.ftth.inventory.MaterialConsumptionApiV2
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class WarehouseCompatibilityITProjection : CustomerDeploymentFixture() {
    @Test
    fun `unit bearing material API has a production binding`() {
        assertThat(context.getBeansOfType(MaterialConsumptionApiV2::class.java)).hasSize(1)
    }

    @Test
    fun `verified ONU read adds persisted provenance without changing identity`() {
        val installation = installation()
        val consumed = consume(installation)
        assertThat(consumed.status).withFailMessage(consumed.contentAsString).isEqualTo(201)
        val response = request("GET", "/api/customers/${installation.customer}/onus", installation.receipt.stock.token)
        assertThat(response.status).withFailMessage(response.contentAsString).isEqualTo(200)
        val onu = mapper.readTree(response.contentAsString).single()
        assertThat(onu.path("provenance").asString()).isEqualTo("RECEIPT")
        assertThat(onu.path("assetId").asString()).isEqualTo(installation.receipt.input.lines.single().stockIdentityId.toString())
        assertThat(onu.path("assignmentId").asString()).isEqualTo(installation.operation.toString())
        assertThat(onu.path("retiredAt").isNull).isTrue()
    }

    @Test
    fun `legacy ONU exposes UNKNOWN with nullable historic references`() {
        val installation = installation()
        val id = LegacyOnuTestFixture.stage(installation.customer.toString(), "UNRESOLVED-COMPATIBILITY")
        val response = request("GET", "/api/customers/${installation.customer}/onus", installation.receipt.stock.token)
        assertThat(response.status).isEqualTo(200)
        val onu = mapper.readTree(response.contentAsString).single()
        assertThat(onu.path("id").asString()).isEqualTo(id)
        assertThat(onu.path("provenance").asString()).isEqualTo("UNKNOWN")
        for (field in listOf("assetId", "assignmentId", "retiredAt")) assertThat(onu.path(field).isNull).describedAs(field).isTrue()
        assertUninstalled(installation)
    }
}

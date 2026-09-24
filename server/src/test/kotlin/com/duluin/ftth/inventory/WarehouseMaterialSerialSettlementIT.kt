package com.duluin.ftth.inventory

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class WarehouseMaterialSerialSettlementIT : WarehouseReturnAssetFixture() {
    @ParameterizedTest @ValueSource(strings = ["LOAN", "SALE"])
    fun `actual device deployment discharges its issue quantity exactly once before and after title handover`(mode: String) {
        val installed = ownershipCase(mode)
        val receipt = installed.installation.receipt
        val issued = receipt.input.lines.single().issueLineId.toString()
        fun verify() {
            val response = request("GET", "/api/work-orders/${receipt.workOrder}/materials/settlement", receipt.stock.token)
            assertThat(response.status).withFailMessage(response.contentAsString).isEqualTo(200)
            val body = mapper.readTree(response.contentAsString)
            val line = body.path("obligations").path("lines").single { it.path("issueLineId").asString() == issued }
            assertThat(line.path("issuedBase").asString()).isEqualTo("1")
            assertThat(line.path("usedBase").asString()).isEqualTo("1")
            assertThat(line.path("returnedBase").asString()).isEqualTo("0")
            assertThat(line.path("stillAccountableBase").asString()).isEqualTo("0")
            assertThat(line.path("transitBase").asString()).isEqualTo("0")
            // The second physical device is still dispatched, not acknowledged.
            assertThat(body.path("outstandingBase").asString()).isEqualTo("1")
        }
        verify()
        assertThat(accept(installed).status).isEqualTo(200)
        verify()
    }
}

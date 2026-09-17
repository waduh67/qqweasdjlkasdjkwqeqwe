package com.duluin.ftth.customer

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class WarehouseCompatibilityITSubscriber : WarehouseCompatibilityMaterialFixture() {
    @Test
    fun `Subscriber360 exposes V2 explicitly and does not change legacy materialHistory`() {
        val installation = mixedMaterials()
        val response = request("GET", "/api/subscriber-360/${installation.customer}", installation.receipt.stock.token)
        assertThat(response.status).withFailMessage(response.contentAsString).isEqualTo(200)
        val view = mapper.readTree(response.contentAsString)
        assertThat(view.path("materialHistory").asSequence().map { it.path("quantity").asInt() }.toList()).containsExactly(37)
        assertThat(view.path("materialHistoryV2").path("totalElements").asLong()).isEqualTo(2)
        assertThat(view.path("materialHistoryV2").path("items").asSequence().map { it.path("quantity").path("quantityBase").asString() }.toList()).containsExactly("1", "82500")
    }
}

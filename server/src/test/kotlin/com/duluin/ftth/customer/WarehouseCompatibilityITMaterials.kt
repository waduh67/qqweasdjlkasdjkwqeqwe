package com.duluin.ftth.customer

import com.duluin.ftth.inventory.MaterialConsumptionApi
import com.duluin.ftth.inventory.MaterialConsumptionApiV2
import com.duluin.ftth.inventory.WarehouseBaseUnit
import com.duluin.ftth.inventory.WarehousePageRequest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class WarehouseCompatibilityITMaterials : WarehouseCompatibilityMaterialFixture() {
    @Test
    fun `V2 preserves exact measured and serialized facts while legacy count history stays unchanged`() {
        val installation = mixedMaterials()
        val stock = fixture(installation.receipt.stock.token)
        stock.transaction {
            val api = context.getBean(MaterialConsumptionApiV2::class.java)
            val page = api.forCustomer(installation.customer, WarehousePageRequest())
            assertThat(page.totalElements).isEqualTo(2)
            assertThat(page.items.map { it.quantity.quantityBase }).containsExactly("1", "82500")
            assertThat(page.items.map { it.quantity.baseUnit }).containsExactly(WarehouseBaseUnit.EA, WarehouseBaseUnit.MM)
            assertThat(page.items.last().quantity.displayQuantity).isEqualTo("82.500")
            assertThat(page.items.last().skuId.toString()).isEqualTo(installation.receipt.stock.cable)
            assertThat(page.items.all { it.customerId == installation.customer && it.useRevision > 0 }).isTrue()
            assertThat(page.items.map { it.postingId }).doesNotHaveDuplicates()
            @Suppress("DEPRECATION")
            val legacy = context.getBean(MaterialConsumptionApi::class.java).forCustomer(tenant, installation.customer)
            assertThat(legacy.map { it.quantity }).containsExactly(37)
            val json = mapper.valueToTree<tools.jackson.databind.JsonNode>(legacy)
            assertThat(json.first().path("quantity").isIntegralNumber).isTrue()
            assertThat(json.first().propertyNames()).containsExactlyInAnyOrder("tenantId", "customerId", "workOrderId", "itemCategory",
                "quantity", "installed", "returned", "recordedAt")
            val first = api.forCustomer(installation.customer, WarehousePageRequest(0, 1))
            val second = api.forCustomer(installation.customer, WarehousePageRequest(1, 1))
            assertThat(first.items + second.items).isEqualTo(page.items)
            assertThat(api.forCustomer(installation.customer, WarehousePageRequest(2, 1)).totalElements).isEqualTo(2)
            assertThat(api.forCustomer(installation.customer, WarehousePageRequest(2, 1)).items).isEmpty()
            assertThat(api.forCustomer(UUID.randomUUID(), WarehousePageRequest()).items).isEmpty()
        }
        fixture(tenant()).transaction {
            val page = context.getBean(MaterialConsumptionApiV2::class.java).forCustomer(installation.customer, WarehousePageRequest())
            assertThat(page.totalElements).isZero()
            assertThat(page.items).isEmpty()
        }
    }

}

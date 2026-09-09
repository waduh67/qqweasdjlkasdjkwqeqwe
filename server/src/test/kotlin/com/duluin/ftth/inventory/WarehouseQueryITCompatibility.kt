package com.duluin.ftth.inventory

import com.duluin.ftth.customer.PortalCustomerAsset
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class WarehouseQueryITCompatibility : WarehouseReceiptHttpFixture() {
    @Test fun `legacy stock and asset DTOs retain exact serialized integer semantics with measured stock present`() {
        val setup = setupReceipt()
        val receipt = draft(setup, """{"skuId":"${setup.cable}","quantityBase":"82501","lotCode":"FRACTION"},
            {"skuId":"${setup.onu}","quantityBase":"2","serials":[{"serial":"DEVICE-1"},{"serial":"DEVICE-2"}]}""")
        transition(setup, receipt.path("id").asString(), "receive", """{"expectedRevision":0}""")
        val stock = request("GET", "/api/inventory/stock", setup.token)
        assertThat(stock.status).isEqualTo(200)
        assertThat(mapper.readTree(stock.contentAsString)).isEqualTo(mapper.readTree(
            """[{"skuId":"${setup.onu}","locationId":"${setup.inspection}","quantities":{"QUARANTINE":2}}]"""))
        val items = mapper.readTree(request("GET", "/api/inventory/items", setup.token).contentAsString)
        assertThat(items.size()).isEqualTo(2)
        items.forEach { item ->
            assertThat(item.propertyNames()).containsExactlyInAnyOrder("id", "skuId", "serialNumber", "macAddress", "status")
            assertThat(item.path("skuId").asString()).isEqualTo(setup.onu)
            val lookup = mapper.readTree(request("GET", "/api/v1/warehouse/assets/lookup?value=${item.path("serialNumber").asString()}", setup.token).contentAsString)
            assertThat(lookup.propertyNames()).containsExactlyInAnyOrder("assetId", "skuId", "serial", "mac", "locationId", "legacyUnresolved")
            assertThat(lookup.path("assetId")).isEqualTo(item.path("id"))
            fixture(setup.token).transaction {
                val ref = context.getBean(InventoryApi::class.java).findSerializedAsset(UUID.fromString(item.path("id").asString()))
                assertThat(mapper.valueToTree<tools.jackson.databind.JsonNode>(ref).propertyNames()).containsExactlyInAnyOrder("assetId", "tenantId", "skuId", "serialNumber",
                    "macAddress", "status", "locationId", "custodyOwnerId", "installedOnuId")
            }
        }
    }

    @Test fun `portal asset remains a separate public-only DTO`() {
        val node = mapper.valueToTree<tools.jackson.databind.JsonNode>(PortalCustomerAsset("ONU", "DEVICE-1", AssetOwnershipMode.LOAN, AssetLegalOwner.ISP,
            AssetProvenance.RECEIPT, Instant.parse("2026-01-01T00:00:00Z"), null))
        assertThat(node.propertyNames()).containsExactlyInAnyOrder("deviceLabel", "serialNumber", "ownershipMode", "legalOwner",
            "provenance", "installedAt", "removedAt")
    }
}

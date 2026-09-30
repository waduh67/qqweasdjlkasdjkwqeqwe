package com.duluin.ftth.inventory

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class WarehouseReceiptIT : WarehouseMasterHttpFixture() {
    @Test fun `receipt draft expands exact serial and measured reel intake without creating stock`() {
        val token = tenant()
        val supplier = create("suppliers", token, """{"code":"SUP","name":"Supplier"}""").path("id").asString()
        val source = create("locations", token, """{"code":"RECEIPT_SOURCE","name":"Supplier boundary","kind":"TRANSIT"}""").path("id").asString()
        val inspection = create("locations", token, """{"code":"INSPECT","name":"Inspection","kind":"QUARANTINE"}""").path("id").asString()
        val cable = create("skus", token, """{"code":"CABLE","name":"Cable","tracking":"LOT","baseUnit":"MM"}""").path("id").asString()
        val onu = create("skus", token, """{"code":"ONU","name":"ONU","tracking":"SERIAL","baseUnit":"EA"}""").path("id").asString()
        val serials = (1..10).joinToString(",") { """{"serial":" onu-$it "}""" }
        val body = """{"supplierId":"$supplier","externalReference":"DELIVERY-1","sourceLocationId":"$source","inspectionLocationId":"$inspection","lines":[
            {"skuId":"$cable","quantityBase":"1000000","lotCode":"REEL-1","cost":{"totalMinor":"500000","currency":"IDR"}},
            {"skuId":"$onu","quantityBase":"10","serials":[$serials]}]}"""
        val response = request("POST", "/api/v1/warehouse/receipts", token, body, "draft-key")
        assertThat(response.status).withFailMessage(response.contentAsString).isEqualTo(201)
        val draft = mapper.readTree(response.contentAsString)
        assertThat(draft.path("state").asString()).isEqualTo("DRAFT")
        assertThat(draft.path("lines").size()).isEqualTo(11)
        assertThat(request("POST", "/api/v1/warehouse/receipts", token, body, "draft-key").contentAsString).isEqualTo(response.contentAsString)
        val database = fixture(token)
        database.transaction {
            assertThat(scalar("SELECT current_user")).isEqualTo("warehouse_app")
            assertThat(scalar("SELECT count(*) FROM inventory_movement")).isEqualTo("0")
            assertThat(scalar("SELECT count(*) FROM inventory_segment")).isEqualTo("0")
            assertThat(scalar("SELECT count(*) FROM inventory_identity_claim")).isEqualTo("0")
        }
    }

    @Test fun `legacy inventory read projections stay arrays and do not mint receipt documents`() {
        val token = tenant()
        for (resource in listOf("warehouses", "items", "stock", "reservations", "custody")) {
            val response = request("GET", "/api/inventory/$resource", token)
            assertThat(response.status).isEqualTo(200)
            assertThat(mapper.readTree(response.contentAsString).isArray).isTrue()
        }
        fixture(token).transaction { assertThat(scalar("SELECT count(*) FROM inventory_document")).isEqualTo("0") }
    }
}

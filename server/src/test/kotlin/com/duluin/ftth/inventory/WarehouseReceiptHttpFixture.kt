package com.duluin.ftth.inventory

import org.assertj.core.api.Assertions.assertThat
import tools.jackson.databind.JsonNode

abstract class WarehouseReceiptHttpFixture : WarehouseMasterHttpFixture() {
    protected data class Setup(val token: String, val supplier: String, val source: String, val inspection: String,
        val bin: String, val cable: String, val onu: String)
    protected fun setupReceipt(): Setup {
        val token = tenant()
        fun master(resource: String, body: String) = create(resource, token, body).path("id").asString()
        val supplier = master("suppliers", """{"code":"SUP","name":"Supplier"}""")
        val source = master("locations", """{"code":"RECEIPT_SOURCE","name":"Boundary","kind":"TRANSIT"}""")
        val inspection = master("locations", """{"code":"INSPECT","name":"Inspection","kind":"QUARANTINE"}""")
        val warehouse = master("locations", """{"code":"WH","name":"Warehouse","kind":"WAREHOUSE"}""")
        val bin = master("locations", """{"code":"BIN","name":"Serviceable","kind":"BIN","parentLocationId":"$warehouse","issueEligible":true}""")
        val cable = master("skus", """{"code":"CABLE","name":"Cable","tracking":"LOT","baseUnit":"MM"}""")
        val onu = master("skus", """{"code":"ONU","name":"ONU","tracking":"SERIAL","baseUnit":"EA"}""")
        return Setup(token, supplier, source, inspection, bin, cable, onu)
    }
    protected fun draftBody(setup: Setup, lines: String) = """{"supplierId":"${setup.supplier}","externalReference":"DELIVERY-1",
        "sourceLocationId":"${setup.source}","inspectionLocationId":"${setup.inspection}","lines":[$lines]}"""
    protected fun draft(setup: Setup, lines: String): JsonNode = create("receipts", setup.token, draftBody(setup, lines))
    protected fun transition(setup: Setup, id: String, action: String, body: String, key: String = java.util.UUID.randomUUID().toString()): JsonNode {
        val response = request("POST", "/api/v1/warehouse/receipts/$id/$action", setup.token, body, key)
        assertThat(response.status).withFailMessage(response.contentAsString).isEqualTo(200)
        return mapper.readTree(response.contentAsString)
    }
}

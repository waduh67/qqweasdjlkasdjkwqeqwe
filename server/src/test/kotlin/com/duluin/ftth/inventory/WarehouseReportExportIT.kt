package com.duluin.ftth.inventory

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class WarehouseReportExportIT : WarehouseReceiptHttpFixture() {
    @Test fun `more than one thousand actual ledger entries require a narrower export`() {
        val stock = setupReceipt()
        assertThat(request("PUT", "/api/v1/warehouse/skus/${stock.onu}", stock.token,
            """{"expectedRevision":0,"code":"ONU","name":"Export capacity","tracking":"SERIAL","baseUnit":"EA","inspectionRequired":false}""").status).isEqualTo(200)
        val receipt = draft(stock, mapper.writeValueAsString(mapOf("skuId" to stock.onu, "quantityBase" to "334",
            "serials" to (1..334).map { mapOf("serial" to "EXPORT-$it") }))).path("id").asString()
        transition(stock, receipt, "receive", """{"expectedRevision":0}""")
        val received = mapper.readTree(request("GET", "/api/v1/warehouse/receipts/$receipt", stock.token).contentAsString)
        val lines = received.path("lines").asSequence().flatMap { line -> line.path("pieces").asSequence().map { piece ->
            mapOf("lineId" to line.path("id").asString(), "stockIdentityId" to piece.path("stockIdentityId").asString(), "quantityBase" to "1", "baseUnit" to "EA")
        } }.toList()
        transition(stock, receipt, "putaway", mapper.writeValueAsString(mapOf("expectedRevision" to 1,
            "destinationLocationId" to stock.bin, "lines" to lines)))
        val summary = request("GET", "/api/v1/warehouse/reports/movements?size=1", stock.token)
        assertThat(summary.status).withFailMessage(summary.contentAsString).isEqualTo(200)
        assertThat(mapper.readTree(summary.contentAsString).path("totalElements").asInt()).isEqualTo(1002)
        val tooLarge = request("GET", "/api/v1/warehouse/reports/movements/export.csv", stock.token)
        assertThat(tooLarge.status).withFailMessage(tooLarge.contentAsString).isEqualTo(400)
        assertThat(mapper.readTree(tooLarge.contentAsString).path("code").asString()).isEqualTo("MALFORMED_REQUEST")
        val narrowed = request("GET", "/api/v1/warehouse/reports/movements/export.csv?locationId=${stock.bin}", stock.token)
        assertThat(narrowed.status).withFailMessage(narrowed.contentAsString).isEqualTo(200)
        assertThat(narrowed.contentAsString.lines().filter(String::isNotBlank)).hasSize(335)
    }
}

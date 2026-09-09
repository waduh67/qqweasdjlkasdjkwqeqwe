package com.duluin.ftth.inventory

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class WarehouseQueryITIdentity : WarehouseReceiptHttpFixture() {
    @Test fun `asset detail and immutable timeline expose real receipt origin and no fabricated movements`() {
        val setup = setupReceipt()
        val draft = draft(setup, """{"skuId":"${setup.onu}","quantityBase":"1","serials":[{"serial":"TIMELINE"}],"cost":{"totalMinor":"25000","currency":"IDR"}}""")
        val id = draft.path("id").asString()
        transition(setup, id, "receive", """{"expectedRevision":0}""")
        val lookup = mapper.readTree(request("GET", "/api/v1/warehouse/assets/lookup?value=TIMELINE", setup.token).contentAsString)
        val assetId = lookup.path("assetId").asString()
        val response = request("GET", "/api/v1/warehouse/assets/$assetId", setup.token)
        assertThat(response.status).withFailMessage(response.contentAsString).isEqualTo(200)
        val asset = mapper.readTree(response.contentAsString)
        assertThat(asset.path("assetId").asString()).isEqualTo(assetId)
        assertThat(asset.path("origin").path("documentId").asString()).isEqualTo(id)
        assertThat(asset.path("cost").path("totalMinor").asString()).isEqualTo("25000")
        val history = request("GET", "/api/v1/warehouse/assets/$assetId/history", setup.token)
        assertThat(history.status).withFailMessage(history.contentAsString).isEqualTo(200)
        val events = mapper.readTree(history.contentAsString).path("items")
        assertThat(events.size()).isEqualTo(2)
        assertThat(events.asSequence().map { it.path("direction").asString() }.toList()).containsExactlyInAnyOrder("OUT", "IN")
        assertThat(events.all { it.path("documentId").asString() == id }).isTrue()
        assertThat(events.all { it.path("recordedAt").asString().endsWith("Z") }).isTrue()
        assertThat(request("GET", "/api/v1/warehouse/assets/$assetId/history", tenant()).status).isEqualTo(404)
    }

    @Test fun `lot detail and segment page preserve exact reel capacity and origin`() {
        val setup = setupReceipt()
        val draft = draft(setup, """{"skuId":"${setup.cable}","quantityBase":"1000001","lotCode":"REEL","cost":{"totalMinor":"500000","currency":"IDR"}}""")
        transition(setup, draft.path("id").asString(), "receive", """{"expectedRevision":0}""")
        val response = request("GET", "/api/v1/warehouse/lots", setup.token)
        assertThat(response.status).withFailMessage(response.contentAsString).isEqualTo(200)
        val lot = mapper.readTree(response.contentAsString).path("items").single()
        val id = lot.path("id").asString()
        val detail = mapper.readTree(request("GET", "/api/v1/warehouse/lots/$id", setup.token).contentAsString)
        assertThat(detail.path("received").path("quantityBase").asString()).isEqualTo("1000001")
        assertThat(detail.path("received").path("displayQuantity").asString()).isEqualTo("1000.001")
        assertThat(detail.path("conservation").path("consistent").asBoolean()).isTrue()
        val segments = mapper.readTree(request("GET", "/api/v1/warehouse/lots/$id/segments", setup.token).contentAsString)
        assertThat(segments.path("totalElements").asInt()).isEqualTo(1)
        assertThat(segments.path("items")[0].path("kind").asString()).isEqualTo("REEL")
        val segmentId = segments.path("items")[0].path("id").asString()
        assertThat(request("GET", "/api/v1/warehouse/lots/$id/segments/$segmentId", setup.token).status).isEqualTo(200)
        assertThat(mapper.readTree(request("GET", "/api/v1/warehouse/lots/$id/segments?status=SPLIT", setup.token).contentAsString).path("totalElements").asInt()).isZero()
        assertThat(request("GET", "/api/v1/warehouse/lots/$id", tenant()).status).isEqualTo(404)
    }
}

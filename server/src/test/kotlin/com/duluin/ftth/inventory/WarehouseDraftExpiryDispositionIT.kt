package com.duluin.ftth.inventory

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class WarehouseDraftExpiryDispositionIT : WarehouseDispositionFixture() {
    @ParameterizedTest @ValueSource(strings = ["LOSS", "SCRAP"])
    fun `due disposition preserves physical return and permits a fresh proposal`(action: String) {
        val case = dispositionCase(WarehouseDispositionAction.valueOf(action))
        verify(case, "/api/v1/warehouse/dispositions", mapper.writeValueAsString(case.input))
    }

    @ParameterizedTest @ValueSource(strings = ["LOSS", "SCRAP"])
    fun `due compensation preserves the original posted disposition`(action: String) {
        val case = dispositionCase(WarehouseDispositionAction.valueOf(action))
        val original = disposition(case)
        assertThat(dispositionDecision(case, dispositionApproval(case, original)).status).isEqualTo(200)
        val body = """{"expectedRevision":1,"expectedReturnRevision":2,"destinationLocationId":"${case.residual.input.targetLocationId}",
            "reason":"Witnessed recovery of disposed material","evidenceReference":"signed-recovery"}"""
        verify(case, "/api/v1/warehouse/dispositions/$original/compensations", body)
        fixture(case.token).transaction {
            assertThat(scalar("SELECT state FROM inventory_document WHERE id='$original'")).isEqualTo("POSTED")
            assertThat(scalar("SELECT count(*) FROM inventory_document_draft_expiry WHERE document_id='$original'")).isEqualTo("0")
        }
    }

    private fun verify(case: DispositionCase, path: String, body: String) {
        val database = fixture(case.token)
        val clock = WarehouseDraftClockFixture(database)
        clock.policy(1)
        val original = request("POST", path, case.token, body, "expiry-original")
        assertThat(original.status).withFailMessage(original.contentAsString).isEqualTo(201)
        val id = mapper.readTree(original.contentAsString).path("id").asString()
        val before = WarehouseDraftExpiryFacts.capture(database, id)
        val activity = clock.activity(id)
        clock.awaitDocument(id)
        val detail = request("GET", "$path/$id", case.token)
        assertThat(detail.status).withFailMessage(detail.contentAsString).isEqualTo(200)
        assertThat(mapper.readTree(detail.contentAsString).path("state").asString()).isEqualTo("EXPIRED")
        assertThat(mapper.readTree(detail.contentAsString).path("draftExpiry").path("reason").asString()).isEqualTo("IDLE_DEADLINE")
        val listed = request("GET", path, case.token)
        assertThat(listed.status).withFailMessage(listed.contentAsString).isEqualTo(200)
        assertThat(mapper.readTree(listed.contentAsString).path("items").asSequence().single { it.path("id").asString()==id }
            .path("state").asString()).isEqualTo("EXPIRED")
        WarehouseDraftExpiryFacts.rejected(request("POST", "/api/v1/warehouse/approvals/request", case.token,
            """{"sourceDocumentId":"$id","sourceRevision":0}"""))
        assertThat(request("POST", path, case.token, body, "expiry-original").contentAsString).isEqualTo(original.contentAsString)
        assertThat(clock.activity(id)).isEqualTo(activity)
        WarehouseDraftExpiryFacts.expire(database, id)
        assertThat(WarehouseDraftExpiryFacts.capture(database, id)).isEqualTo(before)
        val replacement = request("POST", path, case.token, body, "expiry-fresh")
        assertThat(replacement.status).withFailMessage(replacement.contentAsString).isEqualTo(201)
        assertThat(mapper.readTree(replacement.contentAsString).path("id").asString()).isNotEqualTo(id)
    }
}

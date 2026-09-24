package com.duluin.ftth.fulfillment

import com.duluin.ftth.inventory.MaterialResidualRequest
import com.duluin.ftth.inventory.application.service.WarehouseCanonicalPayload
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.UUID

class MaterialHandoverPartiesIT : MaterialLifecycleFixture() {
    private data class Parties(val case: ResidualCase, val receiver: Pair<String, String>, val other: Pair<String, String>, val target: String, val input: MaterialResidualRequest)
    private fun parties(): Parties {
        val case = residualCase("7500")
        val receipt = case.usage.receipt
        val receiver = technician(receipt.stock.token)
        val other = technician(receipt.stock.token)
        assertThat(request("POST", "/api/work-orders/${receipt.workOrder}/assign", receipt.stock.token,
            mapper.writeValueAsString(mapOf("technicianIds" to listOf(receiver.second, other.second)))).status).isEqualTo(200)
        val target = create("locations", receipt.stock.token,
            """{"code":"PARTY_FIELD","name":"Named receiver custody","kind":"TECHNICIAN","custodianId":"${receiver.second}"}""").path("id").asString()
        val revision = summary(receipt.stock.token, receipt.workOrder).path("revisions").path("workOrderRevision").asLong()
        return Parties(case, receiver, other, target, case.input.copy(workOrderRevision = revision, targetLocationId = UUID.fromString(target)))
    }

    @Test fun `reviewed sender and receiver cannot change through a location edit without a work order revision change`() {
        val p = parties()
        val receipt = p.case.usage.receipt
        val input = p.input.copy(expectedSenderId = UUID.fromString(receipt.receiver.second), expectedReceiverId = UUID.fromString(p.receiver.second))
        val changed = request("PUT", "/api/v1/warehouse/locations/${p.target}", receipt.stock.token,
            mapper.writeValueAsString(mapOf("expectedRevision" to 0, "code" to "PARTY_FIELD", "name" to "Changed receiver custody", "kind" to "TECHNICIAN",
                "areaId" to area(receipt.stock.token), "custodianId" to p.other.second)))
        assertThat(changed.status).withFailMessage(changed.contentAsString).isEqualTo(200)
        assertThat(summary(receipt.stock.token, receipt.workOrder).path("revisions").path("workOrderRevision").asLong()).isEqualTo(input.workOrderRevision)
        // The deliberate master edit records its own operation; only the rejected commands must be inert.
        val before = usageAccounting(p.case.usage)
        val endpoint = "/api/work-orders/${receipt.workOrder}/materials/handover/authorize"
        val stale = request("POST", endpoint, receipt.stock.token, mapper.writeValueAsString(input), "reviewed-parties")
        assertThat(stale.status).withFailMessage(stale.contentAsString).isEqualTo(409)
        assertThat(request("POST", endpoint, receipt.stock.token, mapper.writeValueAsString(input.copy(expectedSenderId = UUID.randomUUID(), expectedReceiverId = UUID.fromString(p.other.second))), "wrong-sender").status).isEqualTo(409)
        assertThat(usageAccounting(p.case.usage)).isEqualTo(before)
        val renewed = request("POST", endpoint, receipt.stock.token, mapper.writeValueAsString(input.copy(expectedReceiverId = UUID.fromString(p.other.second))), "reviewed-new-parties")
        assertThat(renewed.status).withFailMessage(renewed.contentAsString).isEqualTo(200)
        assertThat(mapper.readTree(renewed.contentAsString).path("receiverId").asString()).isEqualTo(p.other.second)
    }

    @Test fun `legacy request hashes remain stable and database rejects forged named parties or source scope`() {
        val p = parties()
        val receipt = p.case.usage.receipt
        val body = mapper.writeValueAsString(p.input)
        assertThat(body).doesNotContain("expectedSenderId", "expectedReceiverId")
        val endpoint = "/api/work-orders/${receipt.workOrder}/materials/handover/authorize"
        val original = request("POST", endpoint, receipt.stock.token, body, "legacy-request-shape")
        assertThat(original.status).withFailMessage(original.contentAsString).isEqualTo(200)
        val id = mapper.readTree(original.contentAsString).path("authorizationId").asString()
        fixture(receipt.stock.token).transaction {
            assertThat(scalar("SELECT payload_hash FROM inventory_material_handover WHERE id='$id'")).isEqualTo(WarehouseCanonicalPayload.parse(body).hash)
        }
        assertThat(request("POST", endpoint, receipt.stock.token, body, "legacy-request-shape").contentAsString).isEqualTo(original.contentAsString)
        val named = p.input.copy(expectedSenderId = UUID.fromString(receipt.receiver.second), expectedReceiverId = UUID.fromString(p.receiver.second))
        assertThat(request("POST", endpoint, receipt.stock.token, mapper.writeValueAsString(named), "legacy-request-shape").status).isEqualTo(409)
        assertThat(request("POST", endpoint, receipt.stock.token, mapper.writeValueAsString(p.input.copy(expectedSenderId = named.expectedSenderId)), "half-bound").status).isEqualTo(400)
        for (patch in listOf(
            "jsonb_build_object('request',body::jsonb->'request' || jsonb_build_object('expectedSenderId',sender_id,'expectedReceiverId','${UUID.randomUUID()}'::uuid))",
            "jsonb_build_object('sourceLocationId','${UUID.randomUUID()}'::uuid)")) {
            val forged = UUID.randomUUID()
            assertThatThrownBy {
                fixture(receipt.stock.token).transaction {
                    sql("""INSERT INTO inventory_material_handover(id,tenant_id,work_order_id,work_order_revision,source_identity_id,sender_id,
                        receiver_id,dispatcher_id,target_location_id,quantity_base,base_unit,operation_key,payload_hash,body,cutover_epoch,recorded_at)
                        SELECT '$forged',tenant_id,work_order_id,work_order_revision,source_identity_id,sender_id,receiver_id,dispatcher_id,
                            target_location_id,quantity_base,base_unit,'forged-$forged',payload_hash,
                            (body::jsonb || jsonb_build_object('authorizationId','$forged'::uuid) || $patch)::text,cutover_epoch,recorded_at
                        FROM inventory_material_handover WHERE id='$id'""")
                }
            }.hasMessageContaining("handover expected parties or source location mismatch")
        }
    }
}

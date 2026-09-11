package com.duluin.ftth.inventory

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.time.Instant

class WarehouseIssueITBinding : WarehouseIssueFixture() {
    @ParameterizedTest @ValueSource(strings = ["extend", "unpick", "release", "reallocate"])
    fun `task10 cannot mutate a reservation bound to a live picked issue`(action: String) {
        val setup = issuedSetup()
        val picked = issueRequest(setup, "pick", pickBody(setup))
        assertThat(picked.status).withFailMessage(picked.contentAsString).isEqualTo(200)
        val issue = mapper.readTree(picked.contentAsString)
        val state = summary(setup.stock.token, setup.workOrder)
        val document = state.path("demandDocumentId").asString()
        val pickedLine = issue.path("lines")[0]
        val requestBody = mutableMapOf<String, Any>("expectedRevision" to state.path("demandRevision").asLong(),
            "workOrderRevision" to state.path("revisions").path("workOrderRevision").asLong(), "planRevision" to 1,
            "allocations" to listOf(mapOf("reservationId" to pickedLine.path("reservationId").asString(),
                "expectedRevision" to pickedLine.path("reservationRevision").asLong(), "quantityBase" to "100000")), "reason" to "Direct task10 mutation")
        if (action == "extend") requestBody["expiresAt"] = Instant.now().plusSeconds(172800).toString()
        if (action == "reallocate") {
            val target = workOrder(setup.stock.token)
            putPlan(setup.stock.token, target, plan(setup.stock.token, target, "[${line(setup.stock.cable)}]"))
            val submitted = action(setup.stock.token, target, "submit-request", command(setup.stock.token, target, 1))
            val targetDocument = submitted.path("demandDocumentId").asString()
            val targetLine = fixture(setup.stock.token).transaction { scalar("SELECT id FROM inventory_document_line WHERE document_id='$targetDocument'") }
            requestBody["target"] = mapOf("documentId" to targetDocument, "expectedRevision" to 1, "workOrderRevision" to 0,
                "planRevision" to 1, "demandLineId" to targetLine)
        }
        val before = binding(setup, issue.path("issueId").asString())
        val denied = request("POST", "/api/v1/warehouse/material-requests/$document/$action", setup.stock.token, mapper.writeValueAsString(requestBody))
        assertThat(denied.status).withFailMessage(denied.contentAsString).isEqualTo(409)
        assertThat(binding(setup, issue.path("issueId").asString())).isEqualTo(before)
        val unpicked = issueRequest(setup, "unpick", transitionBody(setup, issue))
        assertThat(unpicked.status).withFailMessage(unpicked.contentAsString).isEqualTo(200)
        assertThat(binding(setup, issue.path("issueId").asString())).isEqualTo("UNPICKED|2|2|0|100000|1")
        action(setup.stock.token, setup.workOrder, "release", command(setup.stock.token, setup.workOrder, 1, "Released after issue unpick"))
    }
    @Test fun `raw app role updates cannot change picked reservation revisions at commit`() {
        val setup = issuedSetup()
        val result = issueRequest(setup, "pick", pickBody(setup))
        assertThat(result.status).withFailMessage(result.contentAsString).isEqualTo(200)
        val issue = mapper.readTree(result.contentAsString).path("issueId").asString()
        val before = binding(setup, issue)
        assertThatThrownBy { fixture(setup.stock.token).transaction {
            sql("UPDATE inventory_reservation SET expires_at=expires_at+interval '1 hour',revision=revision+1")
        } }.hasStackTraceContaining("warehouse_live_issue_binding_ck")
        assertThat(binding(setup, issue)).isEqualTo(before)
    }
    @Test fun `raw terminal dispatch state cannot bypass the picked binding without posting`() {
        val setup = issuedSetup()
        val picked = issueRequest(setup, "pick", pickBody(setup))
        assertThat(picked.status).withFailMessage(picked.contentAsString).isEqualTo(200)
        val issue = mapper.readTree(picked.contentAsString).path("issueId").asString()
        val before = binding(setup, issue)
        assertThatThrownBy { fixture(setup.stock.token).transaction {
            sql("UPDATE inventory_document SET state='DISPATCHED',revision=revision+1 WHERE id='$issue'")
            sql("UPDATE inventory_reservation SET reserved_picked_base=0,state='DISPATCHED',revision=revision+1")
        } }.hasStackTraceContaining("warehouse_live_issue_binding_ck")
        assertThat(binding(setup, issue)).isEqualTo(before)
    }
    @ParameterizedTest @ValueSource(strings = ["SELECTIVE", "NO_TENANT", "FOREIGN_TENANT", "MISSING_UNPICK", "DISPATCH", "DISPATCH_NO_TENANT"])
    fun `selective deferred checks cannot hide live binding divergence or skip internal tenant assertions`(mode: String) {
        val setup = issuedSetup()
        val picked = issueRequest(setup, "pick", pickBody(setup))
        assertThat(picked.status).withFailMessage(picked.contentAsString).isEqualTo(200)
        val issue = mapper.readTree(picked.contentAsString).path("issueId").asString()
        val before = binding(setup, issue)
        assertThatThrownBy { fixture(setup.stock.token).transaction {
            if (mode == "MISSING_UNPICK") {
                sql("UPDATE inventory_document SET state='UNPICKED',revision=revision+1 WHERE id='$issue'")
                sql("UPDATE inventory_reservation SET reserved_unpicked_base=reserved_picked_base,reserved_picked_base=0,revision=revision+1")
            } else if (mode.startsWith("DISPATCH")) {
                sql("UPDATE inventory_document SET state='DISPATCHED',revision=revision+1 WHERE id='$issue'")
                if (mode == "DISPATCH_NO_TENANT") sql("SET LOCAL app.tenant_id=''")
                sql("SET CONSTRAINTS warehouse_issue_dispatch_posted IMMEDIATE")
            } else {
                sql("UPDATE inventory_reservation SET expires_at=expires_at+interval '1 hour',revision=revision+1")
                if (mode == "NO_TENANT") sql("SET LOCAL app.tenant_id=''")
                if (mode == "FOREIGN_TENANT") sql("SET LOCAL app.tenant_id='${java.util.UUID.randomUUID()}'")
                sql("SET CONSTRAINTS warehouse_issue_reservation_live IMMEDIATE")
            }
        } }.hasStackTraceContaining(if (mode in setOf("NO_TENANT", "FOREIGN_TENANT", "DISPATCH_NO_TENANT")) "row tenant scope" else "warehouse_live_issue_binding_ck")
        assertThat(binding(setup, issue)).isEqualTo(before)
    }
    @Test fun `rejected extension leaves original snapshot revisions dispatchable`() {
        val setup = issuedSetup()
        val picked = issueRequest(setup, "pick", pickBody(setup))
        assertThat(picked.status).withFailMessage(picked.contentAsString).isEqualTo(200)
        val issue = mapper.readTree(picked.contentAsString)
        val state = summary(setup.stock.token, setup.workOrder)
        val line = issue.path("lines")[0]
        val extension = mapper.writeValueAsString(mapOf("expectedRevision" to state.path("demandRevision").asLong(),
            "workOrderRevision" to state.path("revisions").path("workOrderRevision").asLong(), "planRevision" to 1,
            "allocations" to listOf(mapOf("reservationId" to line.path("reservationId").asString(), "expectedRevision" to line.path("reservationRevision").asLong(),
                "quantityBase" to "100000")), "reason" to "Attempted extension", "expiresAt" to Instant.now().plusSeconds(172800).toString()))
        assertThat(request("POST", "/api/v1/warehouse/material-requests/${state.path("demandDocumentId").asString()}/extend", setup.stock.token, extension).status).isEqualTo(409)
        val dispatched = issueRequest(setup, "dispatch", transitionBody(setup, issue))
        assertThat(dispatched.status).withFailMessage(dispatched.contentAsString).isEqualTo(200)
        assertThat(binding(setup, issue.path("issueId").asString())).isEqualTo("DISPATCHED|2|2|0|0|0")
    }
    private fun binding(setup: IssueSetup, id: String) = fixture(setup.stock.token).transaction {
        scalar("""SELECT concat_ws('|',document.state,document.revision,reservation.revision,reservation.reserved_picked_base,
            reservation.reserved_unpicked_base,(SELECT count(*) FROM inventory_issue_unpick WHERE id=document.id))
            FROM inventory_document document JOIN inventory_issue_line line ON line.issue_id=document.id
            JOIN inventory_reservation reservation ON reservation.id=line.reservation_id WHERE document.id='$id'""")
    }
}

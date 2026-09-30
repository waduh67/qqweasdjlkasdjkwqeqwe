package com.duluin.ftth.fulfillment

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class WorkOrderMaterialReceiptITReplayLifecycle : MaterialReceiptFixture() {
    @Test fun `authorized receipt replay survives work order progress but still checks payload and revoked scope`() {
        val case = receiptCase()
        val original = acknowledge(case)
        assertThat(original.status).withFailMessage(original.contentAsString).isEqualTo(200)
        assertThat(request("POST", "/api/work-orders/${case.workOrder}/start", case.receiver.first).status).isEqualTo(200)
        val before = accounting(case)
        val replay = acknowledge(case)
        assertThat(replay.status).withFailMessage(replay.contentAsString).isEqualTo(200)
        assertThat(replay.contentAsString).isEqualTo(original.contentAsString)
        assertThat(acknowledge(case, case.input.copy(evidenceReference = "different-payload")).status).isEqualTo(409)
        assertThat(acknowledge(case, key = "new-action-stale-revision").status).isEqualTo(409)
        assertThat(accounting(case)).isEqualTo(before)
        assertThat(request("PUT", "/api/v1/warehouse/settings/scopes/${case.receiver.second}/${case.field}", case.stock.token,
            """{"expectedRevision":1,"active":false}""").status).isEqualTo(200)
        assertThat(acknowledge(case).status).isEqualTo(404)
        assertThat(accounting(case)).isEqualTo(before)
    }

    @Test fun `new receipt with stale work order revision never reaches physical posting`() {
        val case = receiptCase()
        val before = accounting(case)
        assertThat(request("POST", "/api/work-orders/${case.workOrder}/start", case.receiver.first).status).isEqualTo(200)
        val stale = acknowledge(case)
        assertThat(stale.status).withFailMessage(stale.contentAsString).isEqualTo(409)
        assertThat(mapper.readTree(stale.contentAsString).path("code").asString()).isEqualTo("STALE_REVISION")
        assertThat(accounting(case)).isEqualTo(before)
    }
}

package com.duluin.ftth.fulfillment

import com.duluin.ftth.inventory.WarehouseMasterHttpFixture
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class WorkOrderMaterialsIT : WarehouseMasterHttpFixture() {
    @Test
    fun `standalone preventive work explicitly declares no material and retains revisions`() {
        val token = tenant()
        val created = request("POST", "/api/work-orders", token,
            """{"type":"PREVENTIVE","title":"Inspect network","areaId":"${area(token)}"}""")
        assertThat(created.status).withFailMessage(created.contentAsString).isEqualTo(201)
        val id = mapper.readTree(created.contentAsString).path("id").asString()
        val summary = request("GET", "/api/work-orders/$id/materials", token)
        assertThat(summary.status).withFailMessage(summary.contentAsString).isEqualTo(200)
        val revision = mapper.readTree(summary.contentAsString).path("revisions").path("workOrderRevision").asLong()
        val body = """{"expectedRevision":0,"workOrderRevision":$revision,"materialMode":"NONE","reason":"Inspection only","lines":[]}"""
        val result = request("PUT", "/api/work-orders/$id/materials/plan", token, body, "no-material-plan")
        assertThat(result.status).withFailMessage(result.contentAsString).isEqualTo(200)
        val replay = request("PUT", "/api/work-orders/$id/materials/plan", token, body, "no-material-plan")
        assertThat(replay.contentAsString).isEqualTo(result.contentAsString)
        val current = mapper.readTree(request("GET", "/api/work-orders/$id/materials", token).contentAsString)
        assertThat(current.path("materialMode").asString()).isEqualTo("NONE")
        assertThat(current.path("noMaterialReason").asString()).isEqualTo("Inspection only")
        assertThat(current.path("lines").size()).isZero()
        assertThat(current.path("revisions").path("planRevision").asLong()).isEqualTo(1)
        val submitted = request("POST", "/api/work-orders/$id/materials/submit-request", token,
            """{"expectedRevision":1,"workOrderRevision":$revision}""")
        assertThat(submitted.status).withFailMessage(submitted.contentAsString).isEqualTo(200)
        assertThat(mapper.readTree(submitted.contentAsString).path("demandDocumentId").isNull).isTrue()
        val workOrder = mapper.readTree(request("GET", "/api/work-orders/$id", token).contentAsString)
        assertThat(workOrder.path("workOrder").path("id").asString()).isEqualTo(id)
        assertThat(workOrder.path("workOrder").path("customerId").isNull).isTrue()
    }
}

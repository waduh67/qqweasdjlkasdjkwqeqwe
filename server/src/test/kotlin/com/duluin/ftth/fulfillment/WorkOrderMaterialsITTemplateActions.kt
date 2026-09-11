package com.duluin.ftth.fulfillment

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class WorkOrderMaterialsITTemplateActions : MaterialWorkflowFixture() {
    @Test fun `explicit customer RMA template persists without changing repair action or enabling physical routes`() {
        val setup = setupReceipt()
        val path = "/api/v1/warehouse/material-templates/REPAIR/RETURN_CUSTOMER_RMA"
        val body = """{"expectedRevision":0,"lines":[${line(setup.cable, "11000")}]}"""
        val first = request("PUT", path, setup.token, body, "rma-template")
        assertThat(first.status).withFailMessage(first.contentAsString).isEqualTo(200)
        val snapshot = mapper.readTree(first.contentAsString)
        assertThat(snapshot.path("action").asString()).isEqualTo("RETURN_CUSTOMER_RMA")
        assertThat(request("PUT", path, setup.token, body, "rma-template").contentAsString).isEqualTo(first.contentAsString)
        assertThat(mapper.readTree(request("GET", path, setup.token).contentAsString)).isEqualTo(snapshot)
        val id = workOrder(setup.token, "REPAIR")
        assertThat(summary(setup.token, id).path("template").isNull).isTrue()
        assertThat(request("PUT", "/api/work-orders/$id/materials/plan", setup.token, plan(setup.token, id, null)).status).isEqualTo(409)
        assertThat(request("POST", "/api/work-orders/$id/materials/return", setup.token, "{}").status).isEqualTo(409)
    }
}

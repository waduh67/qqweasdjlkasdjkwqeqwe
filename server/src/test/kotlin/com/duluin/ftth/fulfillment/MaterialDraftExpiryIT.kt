package com.duluin.ftth.fulfillment

import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.inventory.WarehouseDraftClockFixture
import com.duluin.ftth.inventory.application.service.WarehouseDraftExpiryService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class MaterialDraftExpiryIT : MaterialWorkflowFixture() {
    @Test fun `latest expired plan remains current and a replacement uses the next retained revision`() {
        val setup = setupReceipt()
        val database = fixture(setup.token)
        val id = workOrder(setup.token)
        val originalBody = plan(setup.token, id, "[${line(setup.cable)}]")
        val clock = WarehouseDraftClockFixture(database)
        clock.policy(3)
        val first = request("PUT", "/api/work-orders/$id/materials/plan", setup.token, originalBody, "first-plan")
        assertThat(first.status).withFailMessage(first.contentAsString).isEqualTo(200)
        val firstId = mapper.readTree(first.contentAsString).path("id").asString()
        val second = putPlan(setup.token, id, plan(setup.token, id, "[${line(setup.cable, "50000")} ]", 1))
        val secondId = second.path("id").asString()
        clock.awaitPlan(firstId); clock.awaitPlan(secondId)
        val current = summary(setup.token, id)
        assertThat(current.path("plan").path("id").asString()).isEqualTo(secondId)
        assertThat(current.path("planState").asString()).isEqualTo("EXPIRED")
        assertThat(current.path("revisions").path("planRevision").asLong()).isEqualTo(2)
        assertThat(current.path("draftExpiry").path("reason").asString()).isEqualTo("IDLE_DEADLINE")
        val rejected = request("POST", "/api/work-orders/$id/materials/submit-request", setup.token, command(setup.token, id, 2))
        assertThat(rejected.status).withFailMessage(rejected.contentAsString).isEqualTo(409)
        assertThat(mapper.readTree(rejected.contentAsString).path("code").asString()).isEqualTo("DRAFT_EXPIRED")
        assertThat(request("PUT", "/api/work-orders/$id/materials/plan", setup.token, originalBody, "first-plan").contentAsString).isEqualTo(first.contentAsString)
        val history = request("GET", "/api/work-orders/$id/materials/history?page=0&size=25", setup.token)
        assertThat(history.status).withFailMessage(history.contentAsString).isEqualTo(200)
        assertThat(mapper.readTree(history.contentAsString).path("items").asSequence().map { it.path("state").asString() }.toList()).containsExactly("EXPIRED", "EXPIRED")
        clock.policy(31536000)
        val third = putPlan(setup.token, id, plan(setup.token, id, "[${line(setup.cable)}]", 2))
        assertThat(third.path("planRevision").asLong()).isEqualTo(3)
        TenantContext.runAs(database.tenant) {
            val worker = context.getBean(WarehouseDraftExpiryService::class.java)
            assertThat(worker.expireOne()).isTrue(); assertThat(worker.expireOne()).isTrue(); assertThat(worker.expireOne()).isFalse()
        }
        assertThat(summary(setup.token, id).path("plan").path("id")).isEqualTo(third.path("id"))
        database.transaction {
            assertThat(scalar("SELECT count(*) FROM inventory_material_plan WHERE work_order_id='$id'")).isEqualTo("3")
            assertThat(scalar("SELECT count(*) FROM inventory_material_submission")).isEqualTo("0")
            assertThat(scalar("SELECT count(*) FROM inventory_plan_draft_expiry")).isEqualTo("2")
            assertThat(scalar("SELECT count(*) FROM inventory_movement")).isEqualTo("0")
            assertThat(scalar("SELECT count(*) FROM inventory_reservation")).isEqualTo("0")
        }
    }

    @ParameterizedTest @ValueSource(strings = ["NONE", "MATERIAL_REQUIRED"])
    fun `submitted plans are retained outside the idle draft expiry policy`(mode: String) {
        val setup = setupReceipt()
        val database = fixture(setup.token)
        val id = workOrder(setup.token)
        val body = plan(setup.token, id, if (mode == "NONE") "[]" else "[${line(setup.cable)}]", mode = mode, reason = "Planned work")
        val clock = WarehouseDraftClockFixture(database)
        clock.policy(3)
        val saved = putPlan(setup.token, id, body)
        action(setup.token, id, "submit-request", command(setup.token, id, 1))
        clock.awaitPlan(saved.path("id").asString())
        val current = summary(setup.token, id)
        assertThat(current.path("planState").asString()).isEqualTo("SUBMITTED")
        assertThat(current.has("draftExpiry")).isFalse()
        TenantContext.runAs(database.tenant) { assertThat(context.getBean(WarehouseDraftExpiryService::class.java).expireOne()).isFalse() }
    }
}

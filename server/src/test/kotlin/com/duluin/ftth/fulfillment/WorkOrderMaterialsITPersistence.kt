package com.duluin.ftth.fulfillment

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class WorkOrderMaterialsITPersistence : MaterialWorkflowFixture() {
    @Test fun `sealed draft plan snapshot lines commands and submitted history reject direct mutation`() {
        val setup = setupReceipt()
        val id = workOrder(setup.token)
        putPlan(setup.token, id, plan(setup.token, id, "[${line(setup.cable)}]"))
        val fixture = fixture(setup.token)
        listOf("UPDATE inventory_material_plan SET reason='changed',revision=revision+1",
            "UPDATE inventory_material_plan_line SET quantity_base=1,revision=revision+1",
            "DELETE FROM inventory_material_plan_snapshot", "DELETE FROM inventory_material_command").forEach { sql ->
            assertThatThrownBy { fixture.transaction { sql(sql) } }.hasStackTraceContaining(if (sql.startsWith("DELETE")) "append-only" else "immutable")
        }
        action(setup.token, id, "submit-request", command(setup.token, id, 1))
        assertThatThrownBy { fixture.transaction { sql("DELETE FROM inventory_material_submission") } }.hasStackTraceContaining("append-only")
    }
    @Test fun `template versions preserve defaults and prevent archive while current`() {
        val setup = setupReceipt()
        val templatePath = "/api/v1/warehouse/material-templates/PREBROKEN/PREVENTIVE"
        assertThat(request("PUT", templatePath, setup.token, """{"expectedRevision":0,"lines":[${line(setup.cable)}]}""").status).isEqualTo(400)
        val path = "/api/v1/warehouse/material-templates/PREVENTIVE/PREVENTIVE"
        val body = """{"expectedRevision":0,"lines":[${line(setup.cable)}]}"""
        val first = request("PUT", path, setup.token, body, "template-first")
        assertThat(first.status).withFailMessage(first.contentAsString).isEqualTo(200)
        assertThat(request("PUT", path, setup.token, body, "template-first").contentAsString).isEqualTo(first.contentAsString)
        assertThat(request("POST", "/api/v1/warehouse/skus/${setup.cable}/archive", setup.token, """{"expectedRevision":0}""").status).isEqualTo(409)
        assertThat(request("PUT", path, setup.token, """{"expectedRevision":1,"lines":[${line(setup.cable, "200000") }]}""").status).isEqualTo(200)
        fixture(setup.token).transaction {
            assertThat(scalar("SELECT count(*) FROM inventory_material_template")).isEqualTo("2")
            assertThat(scalar("SELECT count(*) FROM inventory_material_template_line")).isEqualTo("2")
        }
    }
    @ParameterizedTest @ValueSource(strings = ["MISSING_REASON", "WRONG_UNIT", "WRONG_LINE"])
    fun `substitution rejects incompatible or unbound replacements`(mode: String) {
        val setup = setupReceipt()
        val id = workOrder(setup.token)
        val original = putPlan(setup.token, id, plan(setup.token, id, "[${line(setup.cable)}]"))
        val replacement = if (mode == "WRONG_UNIT") setup.onu else create("skus", setup.token,
            """{"code":"OTHER","name":"Other cable","tracking":"LOT","baseUnit":"MM"}""").path("id").asString()
        val originalLine = if (mode == "WRONG_LINE") java.util.UUID.randomUUID().toString() else original.path("lines")[0].path("id").asString()
        val reason = if (mode == "MISSING_REASON") "" else "Replacement"
        val substitute = line(replacement, "1", if (mode == "WRONG_UNIT") "EA" else "MM").dropLast(1) +
            """, "substitution":{"originalPlanLineId":"$originalLine","originalSkuId":"${setup.cable}","reason":"$reason"}}"""
        assertThat(request("PUT", "/api/work-orders/$id/materials/plan", setup.token, plan(setup.token, id, "[$substitute]", 1)).status).isIn(400, 409)
    }
}

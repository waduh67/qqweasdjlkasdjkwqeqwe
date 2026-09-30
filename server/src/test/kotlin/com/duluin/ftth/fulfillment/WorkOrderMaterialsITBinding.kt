package com.duluin.ftth.fulfillment

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import com.duluin.ftth.inventory.materialPlanBindingSql
import java.sql.SQLException
import java.util.UUID

class WorkOrderMaterialsITBinding : MaterialWorkflowFixture() {
    @Test fun `AV13 submitted required plan cannot commit without submission and demand`() {
        val setup = setupReceipt()
        val id = workOrder(setup.token)
        val plan = putPlan(setup.token, id, plan(setup.token, id, "[${line(setup.cable)}]"))
        val fixture = fixture(setup.token)
        assertThatThrownBy { fixture.transaction {
            sql("UPDATE inventory_material_plan SET state='SUBMITTED',submitted_at=clock_timestamp(),revision=revision+1 WHERE id='${plan.path("id").asString()}'")
        } }.hasStackTraceContaining("material submission binding")
        val result = summary(setup.token, id)
        assertThat(result.path("demandState").asString()).isEqualTo("DRAFT")
        fixture.transaction { assertThat(scalar("SELECT count(*) FROM inventory_material_submission")).isEqualTo("0") }
    }

    @ParameterizedTest @ValueSource(strings = ["PLAN_FIRST", "SUBMISSION_FIRST", "NONE"])
    fun `deferred binding accepts complete final state independently of submission order`(order: String) {
        val setup = setupReceipt()
        val id = workOrder(setup.token)
        val body = if (order == "NONE") plan(setup.token, id, "[]", mode = "NONE", reason = "Inspection")
            else plan(setup.token, id, "[${line(setup.cable)}]")
        val snapshot = putPlan(setup.token, id, body)
        val planId = UUID.fromString(snapshot.path("id").asString())
        fixture(setup.token).transaction {
            val submit = "UPDATE inventory_material_plan SET state='SUBMITTED',submitted_at=clock_timestamp(),revision=revision+1 WHERE id='$planId'"
            if (order == "PLAN_FIRST") sql(submit)
            materialPlanBindingSql(planId, createSnapshot = false).forEach(::sql)
            if (order != "PLAN_FIRST") sql(submit)
        }
        val summary = summary(setup.token, id)
        assertThat(summary.path("demandState").asString()).isEqualTo("SUBMITTED")
        if (order == "NONE") assertThat(summary.path("demandDocumentId").isNull).isTrue()
        else assertThat(summary.path("lines")[0].path("requestedBase").asString()).isEqualTo("100000")
    }

    @ParameterizedTest @ValueSource(strings = ["MISSING_LINES", "QUANTITY", "WORK_ORDER_REVISION", "PLAN_REVISION", "DUPLICATE_DEMAND"])
    fun `deferred binding rejects incomplete and mismatched demand at commit`(corruption: String) {
        val setup = setupReceipt()
        val id = workOrder(setup.token)
        val snapshot = putPlan(setup.token, id, plan(setup.token, id, "[${line(setup.cable)}]"))
        val planId = UUID.fromString(snapshot.path("id").asString())
        val failure = org.assertj.core.api.Assertions.catchThrowable { fixture(setup.token).transaction {
            sql("UPDATE inventory_material_plan SET state='SUBMITTED',submitted_at=clock_timestamp(),revision=revision+1 WHERE id='$planId'")
            materialPlanBindingSql(planId, createSnapshot = false).forEach { statement ->
                val changed = when {
                    corruption == "MISSING_LINES" && statement.startsWith("INSERT INTO inventory_document_line") -> null
                    corruption == "QUANTITY" -> statement.replace("planned.quantity_base,", "planned.quantity_base+1,")
                    corruption == "WORK_ORDER_REVISION" -> statement.replace("actor_id,work_order_id,work_order_revision,plan_revision,clock_timestamp()", "actor_id,work_order_id,work_order_revision+1,plan_revision,clock_timestamp()")
                    corruption == "PLAN_REVISION" -> statement.replace("actor_id,work_order_id,work_order_revision,plan_revision,clock_timestamp()", "actor_id,work_order_id,work_order_revision,plan_revision+1,clock_timestamp()")
                    else -> statement
                }
                changed?.let(::sql)
            }
            if (corruption == "DUPLICATE_DEMAND") sql("""INSERT INTO inventory_document(id,tenant_id,code,kind,actor_id,work_order_id,work_order_revision,plan_revision,cutover_epoch,authority_epoch)
                SELECT gen_random_uuid(),tenant_id,'duplicate','DEMAND',actor_id,work_order_id,work_order_revision,plan_revision,0,0 FROM inventory_material_plan WHERE id='$planId'""")
        } }
        val databaseFailure = generateSequence(failure) { it.cause }.filterIsInstance<SQLException>().firstOrNull()
        assertThat(databaseFailure != null).describedAs(corruption).isTrue()
        assertThat(databaseFailure?.sqlState).isEqualTo("23514")
        assertThat(failure).hasStackTraceContaining("material submission binding")
    }

    @ParameterizedTest @ValueSource(strings = ["CLEARED", "FOREIGN", "RESTORED", "CORRECT"])
    fun `selective constraint timing cannot hide an invalid binding behind tenant RLS`(scope: String) {
        val setup = setupReceipt()
        val id = workOrder(setup.token)
        val snapshot = putPlan(setup.token, id, plan(setup.token, id, "[${line(setup.cable)}]"))
        val failure = org.assertj.core.api.Assertions.catchThrowable { fixture(setup.token).transaction {
            sql("SET CONSTRAINTS ALL IMMEDIATE")
            sql("SET CONSTRAINTS warehouse_material_submission_binding DEFERRED")
            sql("UPDATE inventory_material_plan SET state='SUBMITTED',submitted_at=clock_timestamp(),revision=revision+1 WHERE id='${snapshot.path("id").asString()}'")
            if (scope != "CORRECT") sql("SET LOCAL app.tenant_id='${if (scope == "FOREIGN") UUID.randomUUID().toString() else ""}'")
            if (scope == "RESTORED") sql("SET LOCAL app.tenant_id='$tenant'")
            sql("SET CONSTRAINTS warehouse_material_submission_binding IMMEDIATE")
        } }
        val databaseFailure = generateSequence(failure) { it.cause }.filterIsInstance<SQLException>().firstOrNull()
        assertThat(databaseFailure?.sqlState).isEqualTo("23514")
        assertThat(failure).hasStackTraceContaining(if (scope in setOf("CLEARED", "FOREIGN")) "row tenant scope" else "material submission binding")
    }

    @ParameterizedTest @ValueSource(strings = ["", "/history"])
    fun `query rejects a visible corrupted binding before commit instead of returning zero success`(suffix: String) {
        val setup = setupReceipt()
        val id = workOrder(setup.token)
        val snapshot = putPlan(setup.token, id, plan(setup.token, id, "[${line(setup.cable)}]"))
        val fixture = fixture(setup.token)
        assertThatThrownBy { fixture.transaction {
            sql("UPDATE inventory_material_plan SET state='SUBMITTED',submitted_at=clock_timestamp(),revision=revision+1 WHERE id='${snapshot.path("id").asString()}'")
            val response = request("GET", "/api/work-orders/$id/materials$suffix", setup.token)
            assertThat(response.status).withFailMessage(response.contentAsString).isEqualTo(409)
            assertThat(mapper.readTree(response.contentAsString).path("code").asString()).isEqualTo("SOURCE_NOT_VERIFIED")
            throw IllegalStateException("rollback visible corrupt binding")
        } }.hasMessage("rollback visible corrupt binding")
    }
}

package com.duluin.ftth.fulfillment

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.util.UUID

class WorkOrderMaterialReworkITIntegrity : MaterialReworkFixture() {
    @ParameterizedTest @ValueSource(strings = ["normal", "cleared", "mismatched", "restored", "selective"])
    fun `deferred rework validation requires its captured tenant after companion validators run`(timing: String) {
        val case = reworkCase()
        val execute = {
            fixture(case.usage.receipt.stock.token).transaction {
                val response = rework(case)
                assertThat(response.status).withFailMessage(response.contentAsString).isEqualTo(200)
                sql("""DO ${'$'}${'$'} DECLARE constraint_row record; BEGIN
                    FOR constraint_row IN SELECT DISTINCT conname FROM pg_constraint
                        WHERE connamespace=current_schema()::regnamespace AND condeferrable AND conname<>'warehouse_material_rework_final' LOOP
                        EXECUTE format('SET CONSTRAINTS %I IMMEDIATE',constraint_row.conname);
                    END LOOP; END ${'$'}${'$'}""")
                when (timing) {
                    "cleared", "selective" -> sql("SET LOCAL app.tenant_id=''")
                    "mismatched" -> sql("SET LOCAL app.tenant_id='${UUID.randomUUID()}'")
                    "restored" -> { sql("SET LOCAL app.tenant_id=''"); sql("SET LOCAL app.tenant_id='$tenant'") }
                    "normal" -> Unit
                    else -> error("Unknown timing")
                }
                sql("SET CONSTRAINTS warehouse_material_rework_final IMMEDIATE")
            }
        }

        if (timing in setOf("normal", "restored")) execute()
        else assertThatThrownBy { execute() }.hasStackTraceContaining("row tenant scope")
    }

    @ParameterizedTest @ValueSource(strings = ["inventory_material_rework", "inventory_material_rework_inherited_line", "inventory_material_rework_evidence"])
    fun `rework references and quantities are append only`(table: String) {
        val case = reworkCase()
        assertThat(rework(case).status).isEqualTo(200)

        assertThatThrownBy { fixture(case.usage.receipt.stock.token).transaction { sql("DELETE FROM $table") } }.isInstanceOf(Exception::class.java)
    }

    @Test fun `raw post-use plan insertion cannot bypass the explicit rework command`() {
        val case = reworkCase()

        assertThatThrownBy { fixture(case.usage.receipt.stock.token).transaction {
            sql("""INSERT INTO inventory_material_plan(id,tenant_id,work_order_id,plan_revision,work_order_revision,material_mode,actor_id,reason)
                SELECT '${UUID.randomUUID()}',tenant_id,work_order_id,plan_revision+1,work_order_revision,material_mode,actor_id,'Raw replacement'
                FROM inventory_material_plan WHERE id='${case.previousPlan.path("id").asString()}'""")
        } }.hasStackTraceContaining("post-use plans require explicit rework lineage")
    }

    @Test fun `evidence references cannot be substituted from a foreign tenant`() {
        val case = reworkCase()
        val foreign = reworkCase()
        val foreignProof = mapper.readTree(request("GET", "/api/work-orders/${foreign.usage.receipt.workOrder}/proof-of-work", foreign.usage.receipt.receiver.first).contentAsString)
        val foreignId = foreignProof.path("artifacts")[0].path("revisionId").asString()

        assertThatThrownBy { fixture(case.usage.receipt.stock.token).transaction {
            val response = rework(case)
            assertThat(response.status).isEqualTo(200)
            val id = mapper.readTree(response.contentAsString).path("reworkId").asString()
            sql("INSERT INTO inventory_material_rework_evidence(tenant_id,rework_id,revision_id,kind,source) VALUES ('$tenant','$id','$foreignId','FAT','PHOTO')")
        } }.hasStackTraceContaining("foreign key")
    }
}

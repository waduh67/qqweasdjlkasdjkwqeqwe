package com.duluin.ftth.fulfillment

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.util.UUID

class WarehouseFulfillmentITBngLineage : BngHandoffFixture() {
    @Test fun `unbound action cannot acquire historical fulfillment lineage after successful replay`() {
        val case=completedHandoff()
        val extra=unbound(case)
        val before=graph(case)
        assertThat(request("POST","/api/work-orders/${case.workOrder}/approve",case.token,"{}").status).isEqualTo(200)

        val failure=runCatching { fixture(case.token).transaction { attach(case,extra) } }.exceptionOrNull()

        val replay=request("POST","/api/work-orders/${case.workOrder}/approve",case.token,"{}")
        val counts=fixture(case.token).transaction {
            scalar("""SELECT cardinality(action_ids)||'|'||(SELECT count(*) FROM bng_action WHERE fulfillment_approval_id=receipt.id AND fulfillment_xid=receipt.created_xid)
                FROM bng_fulfillment_receipt receipt WHERE id='${case.approvalId}'""")
        }
        println("T17-AV-3 commit=${failure==null} counts=$counts replay=${replay.status}")
        assertThat(failure).isInstanceOf(RuntimeException::class.java)
        assertThat(counts).isEqualTo("2|2")
        assertThat(replay.status).isEqualTo(200)
        assertThat(graph(case)).isEqualTo(before)
    }

    @ParameterizedTest @ValueSource(strings=["approval-only","xid-only","bound-null","bound-other","bound-xid","payload","action","delete"])
    fun `post insert provenance and bound payload cannot be changed`(mutation: String) {
        val case=completedHandoff()
        val extra=unbound(case)
        val bound=fixture(case.token).transaction { scalar("SELECT id FROM bng_action WHERE fulfillment_approval_id='${case.approvalId}' ORDER BY id LIMIT 1") }
        val before=graph(case)
        val statement=when(mutation) {
            "approval-only" -> "UPDATE bng_action SET fulfillment_approval_id='${case.approvalId}' WHERE id='$extra'"
            "xid-only" -> "UPDATE bng_action SET fulfillment_xid='${case.xid}'::xid8 WHERE id='$extra'"
            "bound-null" -> "UPDATE bng_action SET fulfillment_approval_id=NULL,fulfillment_xid=NULL WHERE id='$bound'"
            "bound-other" -> "UPDATE bng_action SET fulfillment_approval_id='${UUID.randomUUID()}' WHERE id='$bound'"
            "bound-xid" -> "UPDATE bng_action SET fulfillment_xid=pg_current_xact_id() WHERE id='$bound'"
            "payload" -> "UPDATE bng_action SET username='changed' WHERE id='$bound'"
            "action" -> "UPDATE bng_action SET action='DISCONNECT' WHERE id='$bound'"
            "delete" -> "DELETE FROM bng_action WHERE id='$bound'"
            else -> error("Unknown mutation")
        }

        assertThatThrownBy { fixture(case.token).transaction { sql(statement) } }.isInstanceOf(RuntimeException::class.java)

        assertThat(graph(case)).isEqualTo(before)
        assertThat(request("POST","/api/work-orders/${case.workOrder}/approve",case.token,"{}").status).isEqualTo(200)
    }

    @ParameterizedTest @ValueSource(strings=["no-correlation","historical-xid","applied-scope"])
    fun `bound insert cannot impersonate a completed owner transaction`(binding: String) {
        val case=completedHandoff()
        val before=graph(case)
        val id=UUID.randomUUID()

        assertThatThrownBy { fixture(case.token).transaction {
            if (binding!="no-correlation") sql("SET LOCAL app.fulfillment_approval_id='${case.approvalId}'")
            val xid=if(binding=="applied-scope") "pg_current_xact_id()::text" else "'${case.xid}'"
            sql("""INSERT INTO bng_action SELECT (jsonb_populate_record(NULL::bng_action,to_jsonb(original)||
                jsonb_build_object('id','$id','fulfillment_xid',$xid,'external_id',null))).*
                FROM bng_action original WHERE fulfillment_approval_id='${case.approvalId}' AND action='PROVISION' LIMIT 1""")
        } }.hasStackTraceContaining(if(binding=="applied-scope") "FULFILLMENT_BNG_HANDOFF_SCOPE" else "FULFILLMENT_BNG_INITIAL_BINDING")

        assertThat(graph(case)).isEqualTo(before)
        assertThat(request("POST","/api/work-orders/${case.workOrder}/approve",case.token,"{}").status).isEqualTo(200)
    }

    @ParameterizedTest @ValueSource(strings=["extra","missing","substituted","duplicate","fingerprint","delete"])
    fun `applied receipt preserves its exact action set and fingerprints`(mutation: String) {
        val case=completedHandoff()
        val extra=unbound(case)
        val before=graph(case)
        val change=when(mutation) {
            "extra" -> "action_ids=array_append(action_ids,'$extra'::uuid)"
            "missing" -> "action_ids=action_ids[1:1]"
            "substituted" -> "action_ids=ARRAY[action_ids[1],'$extra'::uuid]"
            "duplicate" -> "action_ids=ARRAY[action_ids[1],action_ids[1]]"
            "fingerprint" -> "action_bindings='{}'::jsonb"
            "delete" -> ""
            else -> error("Unknown mutation")
        }
        val statement=if(mutation=="delete") "DELETE FROM bng_fulfillment_receipt" else "UPDATE bng_fulfillment_receipt SET $change"

        assertThatThrownBy { fixture(case.token).transaction { sql("$statement WHERE id='${case.approvalId}'") } }
            .isInstanceOf(RuntimeException::class.java)

        assertThat(graph(case)).isEqualTo(before)
        assertThat(request("POST","/api/work-orders/${case.workOrder}/approve",case.token,"{}").status).isEqualTo(200)
    }

    @Test fun `ordinary unbound actions retain ordinary lifecycle without fulfillment provenance`() {
        val case=completedHandoff()
        val extra=unbound(case)
        val before=graph(case)

        fixture(case.token).transaction {
            sql("UPDATE bng_action SET status='FAILED',detail='ordinary failure' WHERE id='$extra'")
            assertThat(scalar("SELECT fulfillment_approval_id IS NULL AND fulfillment_xid IS NULL FROM bng_action WHERE id='$extra'")).isEqualTo("t")
            sql("DELETE FROM bng_action WHERE id='$extra'")
        }

        assertThat(graph(case)).isEqualTo(before)
        assertThat(request("POST","/api/work-orders/${case.workOrder}/approve",case.token,"{}").status).isEqualTo(200)
    }
}

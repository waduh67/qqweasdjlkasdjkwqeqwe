package com.duluin.ftth.fulfillment

import com.duluin.ftth.common.tenant.TenantContext
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.util.UUID

class WarehouseFulfillmentITBinding : WarehouseFulfillmentFixture() {
    @Test fun `ambiguous current visit links reject approval with a stable conflict`() {
        val case = usageCase()
        used(case)
        completeJob(case.receipt.workOrder, case.receipt.receiver.first)
        fixture(case.receipt.stock.token).transaction {
            repeat(2) { sql("""INSERT INTO fieldservice_visit(id,tenant_id,order_id,work_order_id,technician_id,state,revision,assignment_active)
                VALUES ('${UUID.randomUUID()}','$tenant','${UUID.randomUUID()}','${case.receipt.workOrder}',
                    '${case.receipt.receiver.second}','PLANNED',0,true)""") }
        }

        val response = request("POST", "/api/work-orders/${case.receipt.workOrder}/approve", case.receipt.stock.token, "{}")

        assertThat(response.status).withFailMessage(response.contentAsString).isEqualTo(409)
        fixture(case.receipt.stock.token).transaction {
            assertThat(scalar("SELECT approval_status FROM work_order WHERE id='${case.receipt.workOrder}'")).isEqualTo("PENDING")
            assertThat(scalar("SELECT count(*) FROM fulfillment_checkpoint")).isEqualTo("0")
        }
    }

    @ParameterizedTest @ValueSource(strings = ["malformed", "unsupported-event", "foreign-tenant"])
    fun `untrusted outbox envelope enters durable reconciliation`(kind: String) {
        val token = tenant()
        val tenant = fixture(token).tenant
        val input = FulfillmentRequest(tenant, "workorder.fulfillment.approve", UUID.randomUUID().toString(), "e".repeat(64),
            FulfillmentSource.WORK_ORDER, UUID.randomUUID(), null, null, "PREVENTIVE", true)
        TenantContext.runAs(tenant) { context.getBean(FulfillmentCoordinator::class.java).accept(input) }
        fixture(token).transaction {
            when (kind) {
                "malformed" -> sql("UPDATE fulfillment_outbox SET payload='not-a-fulfillment-payload'")
                "unsupported-event" -> sql("UPDATE fulfillment_outbox SET event_type='UNSUPPORTED'")
                "foreign-tenant" -> sql("UPDATE fulfillment_outbox SET payload=replace(payload,'$tenant','${UUID.randomUUID()}')")
                else -> error("Unknown envelope")
            }
        }

        val outcome = TenantContext.runAs(tenant) { context.getBean(FulfillmentOutboxWorker::class.java).processNext(tenant, "test-envelope") }

        assertThat(outcome?.state).isEqualTo(FulfillmentState.REQUIRES_RECONCILIATION)
        fixture(token).transaction {
            assertThat(scalar("SELECT state FROM fulfillment_checkpoint")).isEqualTo("REQUIRES_RECONCILIATION")
            assertThat(scalar("SELECT count(*) FROM fulfillment_effect_progress")).isEqualTo("0")
        }
    }

    @Test fun `changed approved WO cannot mint another settlement on approval replay`() {
        val case = approvedCable()
        fixture(case.receipt.stock.token).transaction { sql("UPDATE work_order SET title='Changed after approval' WHERE id='${case.receipt.workOrder}'") }
        val before = physicalState(case.receipt.stock.token)

        val replay = request("POST", "/api/work-orders/${case.receipt.workOrder}/approve", case.receipt.stock.token, "{}")

        assertThat(replay.status).withFailMessage(replay.contentAsString).isEqualTo(409)
        assertThat(physicalState(case.receipt.stock.token)).isEqualTo(before)
        fixture(case.receipt.stock.token).transaction { assertThat(scalar("SELECT count(*) FROM inventory_material_settlement")).isEqualTo("1") }
    }

    @ParameterizedTest
    @ValueSource(strings = ["effects", "progress", "workorder-receipt", "snapshot-update", "snapshot-delete", "receipt-update", "receipt-delete", "receipt-duplicate",
        "checkpoint-subscription", "checkpoint-order", "checkpoint-state"])
    fun `completed settlement rejects mutation or removal of its evidence`(mutation: String) {
        val case = approvedCable()
        val before = physicalState(case.receipt.stock.token)
        val statement = when (mutation) {
            "effects" -> "UPDATE fulfillment_checkpoint SET required_effects=''"
            "progress" -> "DELETE FROM fulfillment_effect_progress WHERE effect_type='INVENTORY'"
            "workorder-receipt" -> "DELETE FROM workorder_fulfillment_result"
            "snapshot-update" -> "UPDATE fulfillment_approval_snapshot SET use_revision=2"
            "snapshot-delete" -> "DELETE FROM fulfillment_approval_snapshot"
            "receipt-update" -> "UPDATE inventory_material_settlement SET use_revision=2"
            "receipt-delete" -> "DELETE FROM inventory_material_settlement"
            "receipt-duplicate" -> "INSERT INTO inventory_material_settlement SELECT * FROM inventory_material_settlement"
            "checkpoint-subscription" -> "UPDATE fulfillment_checkpoint SET subscription_id='${UUID.randomUUID()}'"
            "checkpoint-order" -> "UPDATE fulfillment_checkpoint SET order_id='${UUID.randomUUID()}'"
            "checkpoint-state" -> "UPDATE fulfillment_checkpoint SET state='DISPATCHED'"
            else -> error("Unknown mutation")
        }

        assertThatThrownBy { fixture(case.receipt.stock.token).transaction { sql(statement) } }.isInstanceOf(RuntimeException::class.java)

        assertThat(physicalState(case.receipt.stock.token)).isEqualTo(before)
    }

    @ParameterizedTest
    @ValueSource(strings = ["correct", "cleared", "foreign", "restored", "selective"])
    fun `fabricated snapshot cannot commit under selective tenant timing`(scope: String) {
        val case = approvedCable()
        val id = UUID.randomUUID()
        val key = "fabricated-$id"

        assertThatThrownBy { fixture(case.receipt.stock.token).transaction {
            val tenant = scalar("SELECT current_setting('app.tenant_id')")
            if (scope == "selective") sql("SET CONSTRAINTS ALL IMMEDIATE; SET CONSTRAINTS ALL DEFERRED")
            sql("""INSERT INTO fulfillment_checkpoint(id,tenant_id,namespace,operation_key,canonical_hash,source,target_id,work_order_id,
                work_order_kind,required_effects,approval_actor_id,state,attempts,checkpoint_updated_at)
                SELECT '$id',tenant_id,namespace,'$key',canonical_hash,source,target_id,work_order_id,work_order_kind,required_effects,
                    approval_actor_id,'DISPATCHED',0,now() FROM fulfillment_checkpoint""")
            sql("""INSERT INTO fulfillment_approval_snapshot(id,tenant_id,namespace,operation_key,payload_hash,work_order_id,
                work_order_revision,approved_by,usage_id,use_revision,plan_id,material_mode,required_effects,snapshot,request_payload)
                SELECT '$id',tenant_id,namespace,'$key',payload_hash,work_order_id,work_order_revision+1,approved_by,
                    usage_id,use_revision+1,plan_id,material_mode,required_effects,snapshot,request_payload FROM fulfillment_approval_snapshot""")
            when (scope) {
                "cleared", "selective" -> sql("SET LOCAL app.tenant_id=''")
                "foreign" -> sql("SET LOCAL app.tenant_id='${UUID.randomUUID()}'")
                "restored" -> { sql("SET LOCAL app.tenant_id=''"); sql("SET LOCAL app.tenant_id='$tenant'") }
                "correct" -> Unit
                else -> error("Unknown scope")
            }
        } }.isInstanceOf(RuntimeException::class.java)
    }

    @Test fun `new ambiguous legacy checkpoint cannot fabricate APPLIED state`() {
        val token = tenant()

        assertThatThrownBy { fixture(token).transaction {
            sql("""INSERT INTO fulfillment_checkpoint(id,tenant_id,namespace,operation_key,canonical_hash,source,target_id,state,attempts,checkpoint_updated_at)
                VALUES ('${UUID.randomUUID()}','$tenant','legacy','fabricated','${"a".repeat(64)}','WORK_ORDER','${UUID.randomUUID()}','APPLIED',0,now())""")
        } }.isInstanceOf(RuntimeException::class.java)
    }

    @Test fun `approval and durable delivery replay preserve a single verification receipt`() {
        val case = approvedCable()
        val frozen = frozenRequest(case)
        val before = physicalState(case.receipt.stock.token)

        val replay = request("POST", "/api/work-orders/${case.receipt.workOrder}/approve", case.receipt.stock.token, "{}", "another-key")

        assertThat(replay.status).withFailMessage(replay.contentAsString).isEqualTo(200)
        val outcome = TenantContext.runAs(frozen.tenantId) { context.getBean(FulfillmentCoordinator::class.java).process(frozen) }
        assertThat(outcome.state).isEqualTo(FulfillmentState.APPLIED)
        assertThat(outcome.replayed).isTrue()
        assertThat(physicalState(case.receipt.stock.token)).isEqualTo(before)
        fixture(case.receipt.stock.token).transaction {
            assertThat(scalar("SELECT count(*) FROM inventory_material_settlement")).isEqualTo("1")
            assertThat(scalar("SELECT count(*) FROM fulfillment_approval_snapshot")).isEqualTo("1")
            assertThat(scalar("SELECT count(*) FROM fulfillment_effect_progress WHERE status='COMPLETED'")).isEqualTo("2")
        }
    }
}

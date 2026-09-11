package com.duluin.ftth.fulfillment

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.util.UUID

class WorkOrderMaterialReceiptITIsolation : MaterialReceiptFixture() {
    @ParameterizedTest @ValueSource(strings = ["", "foreign"])
    fun `receipt validator asserts its own tenant even when companion constraints already ran`(mode: String) {
        val case = receiptCase()
        val receipt = mapper.readTree(received(case)).path("receiptId").asString()
        val scope = if (mode.isEmpty()) "" else UUID.randomUUID().toString()

        assertThatThrownBy { fixture(case.stock.token).transaction {
            val tenant = scalar("SELECT current_setting('app.tenant_id')")
            sql("SET CONSTRAINTS ALL IMMEDIATE")
            sql("SET LOCAL app.tenant_id='$scope'")
            sql("SELECT warehouse_assert_material_receipt('$tenant','$receipt')")
        } }.hasStackTraceContaining("row tenant scope")
    }

    @Test fun `cleared and foreign scope hide both receipt tables while restored scope validates`() {
        val case = receiptCase()
        val receipt = mapper.readTree(received(case)).path("receiptId").asString()

        fixture(case.stock.token).transaction {
            val tenant = scalar("SELECT current_setting('app.tenant_id')")
            for (scope in listOf("", UUID.randomUUID().toString())) {
                sql("SET LOCAL app.tenant_id='$scope'")
                assertThat(scalar("SELECT count(*) FROM inventory_material_receipt")).isEqualTo("0")
                assertThat(scalar("SELECT count(*) FROM inventory_material_receipt_line")).isEqualTo("0")
            }
            sql("SET LOCAL app.tenant_id='$tenant'")
            sql("SELECT warehouse_assert_material_receipt('$tenant','$receipt')")
            assertThat(scalar("SELECT count(*) FROM inventory_material_receipt")).isEqualTo("1")
            assertThat(scalar("SELECT count(*) FROM inventory_material_receipt_line")).isEqualTo("1")
        }
    }

    @Test fun `composite receiver foreign key rejects another tenants named receiver`() {
        val case = receiptCase()
        received(case)
        val other = receiptCase()

        assertThatThrownBy { fixture(case.stock.token).transaction {
            sql("""INSERT INTO inventory_material_receipt(id,tenant_id,issue_id,issue_revision,receiver_id,receiver_name,
                evidence_reference,recorded_at,posting_id,snapshot)
                SELECT operation.id,receipt.tenant_id,receipt.issue_id,4,'${other.receiver.second}',receipt.receiver_name,
                    receipt.evidence_reference,receipt.recorded_at,movement.id,receipt.snapshot
                FROM inventory_material_receipt receipt JOIN inventory_operation operation ON operation.document_id=receipt.issue_id AND operation.business_action='DISPATCH'
                JOIN inventory_movement movement ON movement.operation_id=operation.id""")
        } }.hasStackTraceContaining("foreign key constraint")
    }

    @Test fun `receipt cannot acquire extra lines in a later transaction`() {
        val case = receiptCase()
        received(case)

        assertThatThrownBy { fixture(case.stock.token).transaction {
            sql("""INSERT INTO inventory_material_receipt_line SELECT gen_random_uuid(),tenant_id,receipt_id,issue_id,issue_line_id,
                dispatched_identity_id,source_identity_id,source_revision,accepted_identity_id,remaining_identity_id,
                accepted_base,missing_base,rejected_base,prior_accepted_base,remaining_base,base_unit,reason,created_at
                FROM inventory_material_receipt_line""")
        } }.hasStackTraceContaining("receipt lines must be sealed")
    }
}

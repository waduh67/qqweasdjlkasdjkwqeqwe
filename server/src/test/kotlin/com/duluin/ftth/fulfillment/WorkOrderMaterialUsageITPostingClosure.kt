package com.duluin.ftth.fulfillment

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class WorkOrderMaterialUsageITPostingClosure : MaterialUsageFixture() {
    @ParameterizedTest @ValueSource(strings = ["warehouse.material.use", "different.namespace"])
    fun `an immutable usage operation cannot acquire another posting header`(namespace: String) {
        val case = usageCase()
        used(case)
        val before = usageAccounting(case)

        assertThatThrownBy { fixture(case.receipt.stock.token).transaction {
            sql("""INSERT INTO inventory_movement(id,tenant_id,operation_namespace,operation_key,payload_hash,actor_id,reason,
                server_received_at,kind,state,document_id,document_revision,operation_id)
                SELECT gen_random_uuid(),tenant_id,'$namespace',operation_key||'-extra',payload_hash,actor_id,reason,
                    server_received_at,kind,state,document_id,document_revision,operation_id
                FROM inventory_movement WHERE operation_id IN (SELECT id FROM inventory_material_usage)""")
        } }.hasStackTraceContaining("usage")

        assertThat(usageAccounting(case)).isEqualTo(before)
    }

    @Test fun `a posting cannot acquire an extra fact without its usage linkage`() {
        val case = usageCase()
        used(case)
        val before = usageAccounting(case)

        assertThatThrownBy { fixture(case.receipt.stock.token).transaction {
            sql("""INSERT INTO inventory_customer_material_fact(id,tenant_id,customer_id,work_order_id,item_category,quantity,installed,returned,
                recorded_at,operation_key,payload_hash,quantity_base,base_unit,stock_identity_id,lot_id,posting_id,use_revision)
                SELECT gen_random_uuid(),tenant_id,gen_random_uuid(),work_order_id,item_category,quantity,installed,returned,
                    recorded_at,operation_key||'-extra',payload_hash,quantity_base,base_unit,stock_identity_id,lot_id,posting_id,use_revision
                FROM inventory_customer_material_fact""")
        } }.hasStackTraceContaining("usage")

        assertThat(usageAccounting(case)).isEqualTo(before)
    }

    @Test fun `a sealed usage posting cannot acquire another paired set of physical legs`() {
        val case = usageCase()
        used(case)
        val before = usageAccounting(case)

        assertThatThrownBy { fixture(case.receipt.stock.token).transaction {
            sql("""INSERT INTO inventory_movement_leg(id,tenant_id,movement_id,direction,item_id,sku_id,location_id,quantity,serialized,
                custody_owner_id,custody_owner_kind,status,quantity_base,base_unit,stock_identity_id,lot_id,document_line_id,condition,legal_owner,revision)
                SELECT gen_random_uuid(),leg.tenant_id,leg.movement_id,direction.value,leg.item_id,leg.sku_id,leg.location_id,leg.quantity,leg.serialized,
                    leg.custody_owner_id,leg.custody_owner_kind,leg.status,leg.quantity_base,leg.base_unit,leg.stock_identity_id,leg.lot_id,
                    leg.document_line_id,leg.condition,leg.legal_owner,leg.revision
                FROM inventory_movement_leg leg CROSS JOIN (VALUES ('IN'),('OUT')) direction(value)
                WHERE leg.status='CONSUMED' AND leg.direction='IN'""")
        } }.hasStackTraceContaining("usage")

        assertThat(usageAccounting(case)).isEqualTo(before)
    }
}

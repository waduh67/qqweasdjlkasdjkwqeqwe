package com.duluin.ftth.inventory

import java.util.UUID

internal fun materialPlanBindingSql(plan: UUID, existingDemand: UUID? = null, createSnapshot: Boolean = true): List<String> {
    val demand = existingDemand ?: UUID.randomUUID()
    return buildList {
        if (createSnapshot) add("""INSERT INTO inventory_material_plan_snapshot(id,tenant_id,snapshot)
            SELECT plan.id,plan.tenant_id,jsonb_build_object('id',plan.id,'workOrderId',plan.work_order_id,'workOrderCode','Fixture',
                'workType','PREVENTIVE','action','PREVENTIVE','customerId',NULL,'workOrderRevision',plan.work_order_revision,
                'planRevision',plan.plan_revision,'materialMode',plan.material_mode,'reason',plan.reason,'templateId',NULL,
                'actorId',plan.actor_id,'recordedAt',clock_timestamp(),'lines',coalesce((
                    SELECT jsonb_agg(jsonb_build_object('id',line.id,'lineNumber',line.line_number,
                        'sku',jsonb_build_object('id',sku.id,'revision',sku.revision,'code',sku.code,'name',sku.name,'tracking',sku.tracking,'baseUnit',sku.base_unit),
                        'quantityBase',line.quantity_base::text,'continuousCut',line.continuous_cut,'substitution',NULL,'originalSku',NULL) ORDER BY line.line_number)
                    FROM inventory_material_plan_line line JOIN inventory_sku sku ON sku.tenant_id=line.tenant_id AND sku.id=line.sku_id
                    WHERE line.tenant_id=plan.tenant_id AND line.plan_id=plan.id),'[]'::jsonb))::text
                FROM inventory_material_plan plan WHERE plan.id='$plan'""")
        if (existingDemand == null) {
            add("""INSERT INTO inventory_document(id,tenant_id,code,kind,actor_id,work_order_id,work_order_revision,plan_revision,submitted_at,cutover_epoch,authority_epoch)
                SELECT '$demand',tenant_id,'$demand','DEMAND',actor_id,work_order_id,work_order_revision,plan_revision,clock_timestamp(),0,0
                FROM inventory_material_plan WHERE id='$plan' AND material_mode='MATERIAL_REQUIRED'""")
            add("""INSERT INTO inventory_document_line(id,tenant_id,document_id,line_number,document_revision,sku_id,quantity_base,base_unit,tracking,continuous_cut)
                SELECT gen_random_uuid(),planned.tenant_id,'$demand',planned.line_number,0,planned.sku_id,planned.quantity_base,planned.base_unit,sku.tracking,planned.continuous_cut
                FROM inventory_material_plan_line planned JOIN inventory_sku sku ON sku.tenant_id=planned.tenant_id AND sku.id=planned.sku_id WHERE planned.plan_id='$plan'""")
            add("UPDATE inventory_document SET state='SUBMITTED',revision=revision+1 WHERE id='$demand'")
        }
        add("""INSERT INTO inventory_material_submission(id,tenant_id,document_id)
            SELECT id,tenant_id,CASE WHEN material_mode='NONE' THEN NULL ELSE '$demand'::uuid END FROM inventory_material_plan WHERE id='$plan'""")
    }
}

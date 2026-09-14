package com.duluin.ftth.customer

import java.util.UUID

abstract class CustomerDeploymentGraphFixture : CustomerDeploymentFixture() {
    protected fun extraFact(install: Installation): String = """
        INSERT INTO inventory_customer_material_fact(id,tenant_id,customer_id,work_order_id,item_category,quantity,
            installed,returned,recorded_at,operation_key,payload_hash,quantity_base,base_unit,stock_identity_id,posting_id,use_revision,warehouse_admission)
        SELECT '${UUID.randomUUID()}',operation.tenant_id,'${install.customer}','${install.receipt.workOrder}','ONU',1,
            true,false,clock_timestamp(),'extra-${UUID.randomUUID()}',operation.payload_hash,1,'EA',
            '${install.receipt.input.lines.single().stockIdentityId}',result.posting_id,result.use_revision,'VERIFIED'
        FROM inventory_operation operation JOIN inventory_deployment_result result
            ON result.tenant_id=operation.tenant_id AND result.operation_id=operation.id WHERE operation.id='${install.operation}'
    """.trimIndent()

    protected fun clonedDocument(install: Installation, document: UUID): List<String> = listOf(
        """INSERT INTO inventory_document SELECT (jsonb_populate_record(NULL::inventory_document,
            to_jsonb(original)||jsonb_build_object('id','$document','code','ORPHAN-$document','state','DRAFT','revision',0))).*
            FROM inventory_document original WHERE id='${install.operation}'""",
        """INSERT INTO inventory_document_line SELECT (jsonb_populate_record(NULL::inventory_document_line,
            to_jsonb(original)||jsonb_build_object('id','${UUID.randomUUID()}','document_id','$document','document_revision',0,'revision',0))).*
            FROM inventory_document_line original WHERE document_id='${install.operation}'""",
    )
}

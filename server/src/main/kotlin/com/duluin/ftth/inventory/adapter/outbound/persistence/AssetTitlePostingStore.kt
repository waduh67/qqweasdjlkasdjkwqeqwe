package com.duluin.ftth.inventory.adapter.outbound.persistence

import com.duluin.ftth.inventory.application.service.AssetHandoverRecord
import org.springframework.stereotype.Repository

@Repository
class AssetTitlePostingStore(private val jdbc: WarehouseCommandJdbc) {
    fun create(record: AssetHandoverRecord) = jdbc.execute { sql ->
        val assignment = record.assignment
        val position = record.position.dimension
        sql.update("""INSERT INTO inventory_document(id,tenant_id,code,kind,actor_id,work_order_id,customer_id,
            work_order_revision,cutover_epoch,authority_epoch) VALUES (?,?,?,'ASSET_HANDOVER',?,?,?,?,?,?)""",
            record.operationId, sql.tenant, record.code, record.actorId, assignment.workOrderId, assignment.customerId,
            record.workOrder.revision, record.cutoverEpoch, record.authorityEpoch)
        if (assignment.ownershipMode == com.duluin.ftth.inventory.AssetOwnershipMode.SALE &&
            assignment.purpose != com.duluin.ftth.inventory.DeploymentPurpose.RETURN_CUSTOMER_RMA) {
            sql.update("""INSERT INTO inventory_document_line(id,tenant_id,document_id,document_revision,line_number,sku_id,base_unit,
                tracking,quantity_base,stock_identity_id,source_line_id,location_id,custodian_id,custodian_kind,condition,legal_owner)
                VALUES (?,?,?,0,1,?,'EA','SERIAL',1,?,?,?,?,'CUSTOMER','SERVICEABLE','ISP')""", record.operationId,
                sql.tenant, record.operationId, position.skuId, assignment.assetId, assignment.issueLineId, position.locationId, assignment.customerId)
        }
        Unit
    }

    fun acknowledge(record: AssetHandoverRecord) = jdbc.execute { sql ->
        sql.update("""INSERT INTO inventory_operation(id,tenant_id,namespace,operation_key,actor_id,resource_id,resource_scope,
            payload_hash,document_id,document_revision,business_action,original_status,original_body,cutover_epoch,authority_epoch,created_at)
            VALUES (?,?,'warehouse.asset.handover',?,?,?, ?,?,?,1,'ACCEPT',200,?,?,?,?)""", record.operationId, sql.tenant,
            record.operationKey, record.actorId, record.assignment.assignmentId, "workorder:${record.assignment.workOrderId}", record.payloadHash,
            record.operationId, tools.jackson.module.kotlin.jacksonObjectMapper().writeValueAsString(record.assignment),
            record.cutoverEpoch, record.authorityEpoch, record.acceptedAt)
        sql.update("UPDATE inventory_document SET state='POSTED',revision=1 WHERE tenant_id=? AND id=?", sql.tenant, record.operationId)
        Unit
    }
}

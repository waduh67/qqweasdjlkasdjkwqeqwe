package com.duluin.ftth.inventory.adapter.outbound.persistence

import com.duluin.ftth.inventory.application.service.DeploymentPermit
import com.duluin.ftth.inventory.DeploymentBinding
import com.duluin.ftth.inventory.application.port.outbound.PostingDimension
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class DeploymentPostingStore(private val jdbc: WarehouseCommandJdbc) {
    fun create(permit: DeploymentPermit): UUID = create(permit.binding, permit.source.custody, permit.binding.issueLineId!!)

    fun create(binding: DeploymentBinding, custody: PostingDimension, sourceLine: UUID): UUID = jdbc.execute { sql ->
        val line = binding.operationId
        sql.update("""INSERT INTO inventory_document(id,tenant_id,code,kind,actor_id,work_order_id,customer_id,
            work_order_revision,plan_revision,use_revision,cutover_epoch,authority_epoch)
            VALUES (?,?,?,'DEPLOYMENT',?,?,?,?,?,?,?,?)""", binding.operationId, sql.tenant, "INSTALL-${binding.operationId}",
            binding.actorId, binding.workOrderId, binding.customerId, binding.revisions.workOrderRevision,
            binding.revisions.planRevision, Math.addExact(binding.revisions.useRevision, 1), binding.cutoverEpoch, binding.authorityEpoch)
        sql.update("""INSERT INTO inventory_document_line(id,tenant_id,document_id,document_revision,line_number,sku_id,base_unit,
            tracking,quantity_base,stock_identity_id,source_line_id,location_id,custodian_id,custodian_kind,condition,legal_owner)
            VALUES (?,?,?,0,1,?,'EA','SERIAL',1,?,?,?,?,'TECHNICIAN','SERVICEABLE',?)""", line, sql.tenant,
            binding.operationId, custody.skuId, binding.assetId, sourceLine, custody.locationId, binding.actorId, custody.legalOwner)
        line
    }
}

package com.duluin.ftth.inventory.adapter.outbound.persistence

import com.duluin.ftth.inventory.application.port.outbound.*
import com.duluin.ftth.inventory.domain.model.StockUnit

internal class PostingFacts(private val sql: PostingSql) {
    fun write(command: WarehousePost, result: WarehousePostResult) {
        command.facts.forEach { fact ->
            val dimension=command.legs.first { it.dimension.stockIdentityId==fact.stockIdentityId }.dimension
            val legacy=if(fact.quantity.unit==StockUnit.EA && fact.quantity.quantityBase<=Int.MAX_VALUE) fact.quantity.quantityBase.toInt() else null
            val key="${command.operation.id}:${fact.id}"
            sql.update("""INSERT INTO inventory_customer_material_fact(id,tenant_id,customer_id,work_order_id,item_category,quantity,installed,returned,
                recorded_at,operation_key,payload_hash,quantity_base,base_unit,stock_identity_id,lot_id,posting_id,use_revision,compensation_id,usage_id)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",fact.id,sql.tenant,fact.customerId,fact.workOrderId,fact.itemCategory,legacy,fact.installed,fact.returned,
                result.recordedAt,key,command.operation.payloadHash,fact.quantity.quantityBase,fact.quantity.unit,fact.stockIdentityId,dimension.lotId,result.postingId,fact.useRevision,fact.compensatesFactId,fact.usageId)
            fact.fulfillmentTargetId?.let { target -> sql.update("""INSERT INTO inventory_fulfillment_effect(id,tenant_id,target_id,work_order_id,customer_id,namespace,operation_key,payload_hash,
                item_category,quantity,installed,returned,recorded_at,quantity_base,base_unit,stock_identity_id,lot_id,operation_id,posting_id,use_revision)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",fact.id,sql.tenant,target,fact.workOrderId,fact.customerId,command.operation.namespace,command.operation.key,
                command.operation.payloadHash,fact.itemCategory,legacy,fact.installed,fact.returned,result.recordedAt,fact.quantity.quantityBase,fact.quantity.unit,fact.stockIdentityId,
                dimension.lotId,command.operation.id,result.postingId,fact.useRevision) }
        }
        command.usage?.let { usage ->
            require(command.facts.isNotEmpty() && command.facts.all { it.workOrderId==usage.workOrderId && it.useRevision==usage.useRevision })
            require(sql.value("SELECT id FROM inventory_material_plan WHERE tenant_id=? AND id=? AND work_order_id=? AND state='SUBMITTED' AND material_mode='MATERIAL_REQUIRED'",
                sql.tenant,usage.planId,usage.workOrderId)!=null)
            sql.update("""INSERT INTO inventory_usage_snapshot(id,tenant_id,work_order_id,use_revision,plan_id,work_order_revision,operation_id,posting_ids,frozen_snapshot,compensates_snapshot_id)
                VALUES (?,?,?,?,?,?,?,ARRAY[?]::uuid[],?,?)""",usage.id,sql.tenant,usage.workOrderId,usage.useRevision,usage.planId,usage.workOrderRevision,
                command.operation.id,result.postingId,usage.frozenSnapshot,usage.compensatesSnapshotId)
        }
    }
}

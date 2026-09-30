package com.duluin.ftth.inventory.adapter.outbound.persistence

import com.duluin.ftth.iam.CurrentAuthority
import com.duluin.ftth.inventory.WarehouseOperationReceipt
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class WarehouseCountReceipts(private val jdbc: WarehouseCommandJdbc, private val operations: WarehouseOperationStore) {
    fun record(id: UUID, revision: Long, action: String, key: String, hash: String, canonical: String,
        current: CurrentAuthority, cutover: Long, status: Int, body: String): WarehouseOperationReceipt {
        val operation = UUID.randomUUID()
        jdbc.execute { sql ->
            sql.update("""INSERT INTO inventory_operation(id,tenant_id,namespace,operation_key,actor_id,resource_id,resource_scope,payload_hash,
                document_id,document_revision,business_action,original_status,original_body,cutover_epoch,authority_epoch)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""", operation, sql.tenant, "warehouse.count.$action", key, current.fence.identity.userId,
                id, "count:$id", hash, id, revision, "COUNT_${action.uppercase()}", status, body, cutover, current.fence.epoch)
        }
        operations.storeIdentity(operation, canonical, current.fence.identity.sessionId)
        return requireNotNull(operations.findKey("warehouse.count.$action", key)).receipt
    }
}

package com.duluin.ftth.inventory.adapter.outbound.persistence

import com.duluin.ftth.inventory.*
import org.springframework.stereotype.Repository
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.util.UUID

@Repository
class MaterialSettlementStore(private val jdbc: WarehouseCommandJdbc) {
    private val mapper = jacksonObjectMapper()

    fun usageId(workOrder: UUID): UUID = jdbc.execute { sql ->
        sql.query("SELECT id FROM inventory_usage_snapshot WHERE tenant_id=? AND work_order_id=? ORDER BY use_revision DESC LIMIT 1",
            sql.tenant, workOrder) { it.uuid("id") }.singleOrNull() ?: sql.fail(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
    }

    fun lockDocuments(workOrder: UUID): List<MaterialSourceRevision> = jdbc.execute { sql ->
        sql.query("SELECT id,revision FROM inventory_document WHERE tenant_id=? AND work_order_id=? ORDER BY id FOR NO KEY UPDATE",
            sql.tenant, workOrder) { MaterialSourceRevision(it.uuid("id"), it.getLong("revision")) }
    }

    fun lockUsage(id: UUID) = jdbc.execute { sql ->
        sql.value("SELECT id FROM inventory_usage_snapshot WHERE tenant_id=? AND id=? FOR UPDATE", sql.tenant, id)
            ?: sql.fail(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
        val identities = sql.query("""SELECT consumed_identity_id FROM inventory_material_usage_line
            WHERE tenant_id=? AND usage_id=? ORDER BY consumed_identity_id""", sql.tenant, id) { it.uuid("consumed_identity_id") }
        identities.forEach { identity ->
            sql.value("SELECT id FROM inventory_segment WHERE tenant_id=? AND id=? FOR SHARE", sql.tenant, identity)
            sql.query("SELECT id FROM inventory_balance_projection WHERE tenant_id=? AND stock_identity_id=? ORDER BY id FOR SHARE",
                sql.tenant, identity) { it.uuid("id") }
        }
    }

    fun receipts(id: UUID): List<UUID> = jdbc.execute { sql ->
        sql.query("SELECT DISTINCT receipt_id FROM inventory_material_usage_line WHERE tenant_id=? AND usage_id=? ORDER BY receipt_id",
            sql.tenant, id) { it.uuid("receipt_id") }
    }

    fun receipt(id: UUID): MaterialVerificationReceipt? = jdbc.execute { sql ->
        sql.value("SELECT body FROM inventory_material_settlement WHERE tenant_id=? AND id=?", sql.tenant, id)
            ?.let { mapper.readValue(it, MaterialVerificationReceipt::class.java) }
    }

    fun record(approval: MaterialSettlementApproval, receipt: MaterialVerificationReceipt) = jdbc.execute { sql ->
        sql.update("""INSERT INTO inventory_material_settlement(id,tenant_id,usage_id,use_revision,plan_id,payload_hash,material_mode,result,body)
            VALUES (?,?,?,?,?,?,?,?,?)""", approval.id, sql.tenant, approval.source.usageId, approval.source.useRevision,
            approval.source.planId, approval.hash, approval.source.materialMode, receipt.result, mapper.writeValueAsString(receipt))
    }
}

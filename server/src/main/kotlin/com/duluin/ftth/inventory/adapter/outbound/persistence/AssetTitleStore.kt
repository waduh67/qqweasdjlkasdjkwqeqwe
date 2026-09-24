package com.duluin.ftth.inventory.adapter.outbound.persistence

import com.duluin.ftth.inventory.*
import com.duluin.ftth.inventory.application.port.outbound.PostingDimension
import com.duluin.ftth.inventory.application.service.AssetHandoverPosition
import com.duluin.ftth.inventory.application.service.AssetHandoverRecord
import com.duluin.ftth.inventory.domain.model.OwnerKind
import org.springframework.stereotype.Repository
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.util.UUID

@Repository
class AssetTitleStore(private val jdbc: WarehouseCommandJdbc) {
    private val mapper = jacksonObjectMapper()
    fun handover(id: UUID): AssetHandoverRecord = jdbc.execute { sql ->
        sql.value("SELECT snapshot FROM inventory_asset_acceptance WHERE tenant_id=? AND handover_id=?", sql.tenant, id)
            ?.let { mapper.readValue(it, AssetHandoverRecord::class.java) } ?: sql.fail(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
    }
    fun lockAssignment(id: UUID) = jdbc.execute { sql ->
        sql.value("SELECT id FROM inventory_asset_assignment WHERE tenant_id=? AND id=? FOR UPDATE", sql.tenant, id)
            ?: sql.fail(WarehouseErrorCode.NOT_FOUND)
        Unit
    }
    fun current(id: UUID): CurrentAssetOwnership = jdbc.execute { sql ->
        sql.value("SELECT warehouse_assert_current_asset_title(?,?)", sql.tenant, id)
        sql.query("""SELECT assignment.*,handover.id handover_id,latest.id transfer_id,
            (CASE WHEN handover.ownership_mode='SALE' AND assignment.purpose<>'RETURN_CUSTOMER_RMA' THEN 1 ELSE 0 END)+
                (SELECT count(*) FROM inventory_asset_title_transfer WHERE tenant_id=assignment.tenant_id AND assignment_id=assignment.id) title_revision
            FROM inventory_asset_assignment assignment LEFT JOIN inventory_asset_handover handover
                ON handover.tenant_id=assignment.tenant_id AND handover.assignment_id=assignment.id
            LEFT JOIN LATERAL (SELECT id FROM inventory_asset_title_transfer WHERE tenant_id=assignment.tenant_id
                AND assignment_id=assignment.id ORDER BY title_revision DESC LIMIT 1) latest ON true
            WHERE assignment.tenant_id=? AND assignment.id=?""", sql.tenant, id) {
            CurrentAssetOwnership(it.uuid("id"), it.uuid("asset_id"), it.uuid("customer_id"), it.uuid("work_order_id"),
                AssetOwnershipMode.valueOf(it.getString("ownership_mode")), AssetLegalOwner.valueOf(it.getString("legal_owner")),
                it.getLong("revision"), it.getLong("title_revision"), it.optionalUuid("handover_id"), it.optionalUuid("transfer_id"),
                it.getString("legal_owner") == "ISP" && (it.getString("ownership_mode") == "LOAN" || it.optionalUuid("transfer_id") != null))
        }.singleOrNull() ?: sql.fail(WarehouseErrorCode.NOT_FOUND)
    }
    fun position(id: UUID): AssetHandoverPosition = jdbc.execute { sql ->
        sql.query("""SELECT * FROM inventory_serialized_asset WHERE tenant_id=? AND id=? AND warehouse_admission='VERIFIED'
            AND status='CUSTOMER_INSTALLED' AND condition='SERVICEABLE' AND custody_owner_kind='CUSTOMER' FOR UPDATE""", sql.tenant, id) {
            AssetHandoverPosition(PostingDimension(it.uuid("sku_id"), id, null, it.uuid("location_id"), it.uuid("custody_owner_id"),
                OwnerKind.CUSTOMER, WarehouseCondition.SERVICEABLE, AssetLegalOwner.valueOf(it.getString("legal_owner"))), it.getLong("revision"))
        }.singleOrNull() ?: sql.fail(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
    }
    fun assignments(customerId: UUID): List<Pair<UUID, UUID>> = jdbc.execute { sql ->
        sql.query("""SELECT id,work_order_id FROM inventory_asset_assignment WHERE tenant_id=? AND customer_id=?
            AND warehouse_admission='VERIFIED' AND ended_at IS NULL ORDER BY work_order_id,id""", sql.tenant, customerId) {
            it.uuid("id") to it.uuid("work_order_id")
        }
    }
}

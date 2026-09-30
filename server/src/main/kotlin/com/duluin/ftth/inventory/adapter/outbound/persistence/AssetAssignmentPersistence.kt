package com.duluin.ftth.inventory.adapter.outbound.persistence

import com.duluin.ftth.inventory.AssetLegalOwner
import com.duluin.ftth.inventory.AssetOwnershipMode
import com.duluin.ftth.inventory.AssetProvenance
import com.duluin.ftth.inventory.DeploymentPurpose
import com.duluin.ftth.inventory.WarehouseAdmission
import com.duluin.ftth.inventory.WarehouseErrorCode
import com.duluin.ftth.inventory.application.port.outbound.AssetAssignmentClosure
import com.duluin.ftth.inventory.application.port.outbound.AssetAssignmentStore
import com.duluin.ftth.inventory.application.port.outbound.NewAssetAssignment
import com.duluin.ftth.inventory.application.port.outbound.StoredAssetAssignment
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class AssetAssignmentPersistence(private val jdbc: WarehouseCommandJdbc) : AssetAssignmentStore {
    override fun append(assignment: NewAssetAssignment) = jdbc.execute { sql ->
        sql.update("""INSERT INTO inventory_asset_assignment(id,tenant_id,asset_id,customer_id,work_order_id,issue_line_id,
            purpose,ownership_mode,legal_owner,provenance,actor_id,started_at,previous_assignment_id)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)""", assignment.id, sql.tenant, assignment.assetId, assignment.customerId,
            assignment.workOrderId, assignment.issueLineId, assignment.purpose.name, assignment.ownershipMode.name,
            assignment.legalOwner.name, assignment.provenance.name, assignment.actorId, assignment.startedAt, assignment.previousAssignmentId)
        Unit
    }

    override fun close(closure: AssetAssignmentClosure) = jdbc.execute { sql ->
        val updated = sql.update("""UPDATE inventory_asset_assignment SET ended_at=?,revision=revision+1
            WHERE tenant_id=? AND id=? AND revision=? AND ended_at IS NULL AND warehouse_admission='VERIFIED'""",
            closure.endedAt, sql.tenant, closure.id, closure.expectedRevision)
        if (updated != 1) sql.fail(WarehouseErrorCode.STALE_REVISION)
    }

    override fun history(assetId: UUID): List<StoredAssetAssignment> = jdbc.execute { sql ->
        sql.query("""SELECT * FROM inventory_asset_assignment WHERE tenant_id=? AND asset_id=?
            ORDER BY started_at NULLS FIRST,created_at,id""", sql.tenant, assetId) { row ->
            StoredAssetAssignment(row.uuid("id"), row.optionalUuid("asset_id"), row.optionalUuid("customer_id"),
                row.optionalUuid("work_order_id"), row.optionalUuid("issue_line_id"), row.getLong("revision"),
                WarehouseAdmission.valueOf(row.getString("warehouse_admission")),
                row.getString("purpose")?.let(DeploymentPurpose::valueOf),
                row.getString("ownership_mode")?.let(AssetOwnershipMode::valueOf),
                row.getString("legal_owner")?.let(AssetLegalOwner::valueOf),
                AssetProvenance.valueOf(row.getString("provenance")), row.getTimestamp("started_at")?.toInstant(),
                row.getTimestamp("ended_at")?.toInstant())
        }
    }
}

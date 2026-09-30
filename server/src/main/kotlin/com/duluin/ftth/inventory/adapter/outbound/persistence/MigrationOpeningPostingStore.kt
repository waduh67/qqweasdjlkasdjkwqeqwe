package com.duluin.ftth.inventory.adapter.outbound.persistence

import com.duluin.ftth.inventory.*
import com.duluin.ftth.inventory.application.port.outbound.*
import com.duluin.ftth.inventory.application.service.WarehouseCanonicalPayload
import com.duluin.ftth.inventory.domain.model.*
import org.springframework.stereotype.Repository
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.util.UUID

@Repository
class MigrationOpeningPostingStore(private val jdbc: WarehouseCommandJdbc) {
    fun prepare(record: WarehouseApprovalRecord, attempt: WarehouseApprovalAttempt): ReceiptPostingApproval = jdbc.execute { sql ->
        val guard = ReceiptPostingApproval(attempt, record.expiresAt,
            WarehouseCanonicalPayload.parse(jacksonObjectMapper().writeValueAsString(requireNotNull(record.snapshot.evaluation.policy))).hash,
            record.snapshot.sourceHash, record.snapshot.cutoverEpoch, requireNotNull(sql.value("SELECT pg_current_xact_id()::text")), ApprovalPostingKind.OPENING_BALANCE)
        assertReceiptApproval(sql, guard, false)
        if (sql.value("SELECT warehouse_migration_opening_approval_current(?,?,?,false)", sql.tenant, attempt.sourceDocumentId, record.id) != "t")
            throw ApprovalPostingStopped(guard, WarehouseApprovalStatus.STALE)
        guard
    }

    fun admit(document: UUID, approval: UUID, operation: UUID): List<PostingLeg> = jdbc.execute { sql ->
        sql.value("SELECT warehouse_admit_migration_opening(?,?,?)", document, approval, operation)
        sql.value("SELECT warehouse_cancel_migration_effects(?,?)", document, operation)
        sql.query("""SELECT line.* FROM inventory_document_line line JOIN inventory_migration_admission_line binding
            ON binding.tenant_id=line.tenant_id AND binding.line_id=line.id
            WHERE line.tenant_id=? AND line.document_id=? AND binding.request_id=? ORDER BY line.line_number""", sql.tenant, document, document) { row ->
            val dimension = PostingDimension(row.uuid("sku_id"), row.uuid("stock_identity_id"), row.optionalUuid("lot_id"),
                row.uuid("location_id"), row.uuid("custodian_id"), OwnerKind.WAREHOUSE, WarehouseCondition.SERVICEABLE, AssetLegalOwner.ISP)
            val quantity = StockQuantity.of(row.getLong("quantity_base"), StockUnit.valueOf(row.getString("base_unit")))
            listOf(PostingLeg(LegDirection.OUT, dimension, quantity, row.uuid("id"), InventoryStatus.AVAILABLE, PostingEndpoint.RECEIPT_SOURCE),
                PostingLeg(LegDirection.IN, dimension, quantity, row.uuid("id"), InventoryStatus.AVAILABLE))
        }.flatten()
    }
}

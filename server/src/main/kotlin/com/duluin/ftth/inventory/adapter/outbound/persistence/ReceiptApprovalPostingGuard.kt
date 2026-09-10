package com.duluin.ftth.inventory.adapter.outbound.persistence

import com.duluin.ftth.inventory.*
import com.duluin.ftth.inventory.application.port.inbound.ReceiptRecord
import com.duluin.ftth.inventory.application.port.outbound.*
import com.duluin.ftth.inventory.application.service.WarehouseCanonicalPayload
import org.springframework.stereotype.Repository
import tools.jackson.module.kotlin.jacksonObjectMapper

@Repository
class ReceiptApprovalPostingGuard(private val jdbc: WarehouseCommandJdbc, private val approvals: WarehouseApprovalStore,
    private val origins: WarehouseReceiptOrigins) {
    fun prepare(record: WarehouseApprovalRecord, receipt: ReceiptRecord, attempt: WarehouseApprovalAttempt): ReceiptPostingApproval {
        jdbc.execute { sql ->
            sql.update("""LOCK TABLE inventory_approval, inventory_approval_command, inventory_approval_decision,
                inventory_approval_effect, inventory_balance_projection, inventory_command_identity, inventory_document,
                inventory_document_line, inventory_identity_claim, inventory_inbox, inventory_lot, inventory_movement,
                inventory_movement_leg, inventory_operation, inventory_outbox, inventory_segment, inventory_serialized_asset IN ROW EXCLUSIVE MODE""")
            receipt.intake.lines.map { it.sku.id }.distinct().sortedBy { it.toString() }.forEach {
                sql.value("SELECT id FROM inventory_sku WHERE tenant_id=? AND id=? FOR KEY SHARE", sql.tenant, it)
            }
            listOf(receipt.intake.source.id, receipt.intake.inspection.id).sortedBy { it.toString() }.forEach {
                sql.value("SELECT id FROM inventory_location WHERE tenant_id=? AND id=? FOR KEY SHARE", sql.tenant, it)
            }
            sql.value("SELECT id FROM inventory_supplier WHERE tenant_id=? AND id=? FOR KEY SHARE", sql.tenant, receipt.intake.supplier.id)
        }
        origins.lockIdentityKeys(receipt)
        val source = approvals.source(attempt.sourceDocumentId)
        val guard = jdbc.execute { sql -> ReceiptPostingApproval(attempt, record.expiresAt,
            WarehouseCanonicalPayload.parse(jacksonObjectMapper().writeValueAsString(requireNotNull(record.snapshot.evaluation.policy))).hash,
            record.snapshot.sourceHash, record.snapshot.cutoverEpoch, requireNotNull(sql.value("SELECT pg_current_xact_id()::text"))) }
        if (source.kind != "RECEIPT" || source.state != "DRAFT" || source.disposition != null ||
            source.revision != attempt.sourceRevision || WarehouseCanonicalPayload.parse(source.content).hash != guard.sourceHash)
            throw ApprovalPostingStopped(guard, WarehouseApprovalStatus.STALE)
        jdbc.execute { sql -> assertReceiptApproval(sql, guard, false) }
        return guard
    }
    fun beforeAdmission(guard: ReceiptPostingApproval) = jdbc.execute { sql -> assertReceiptApproval(sql, guard, true) }
}

internal fun assertReceiptApproval(sql: PostingSql, guard: ReceiptPostingApproval, decided: Boolean) {
    check(sql.value("SELECT pg_current_xact_id()::text") == guard.transactionId)
    val attempt = guard.attempt
    val states = sql.query("""SELECT approval.status,approval.revision,approval.expires_at,approval.policy_snapshot_hash,
        approval.policy_version_id,approval.source_snapshot_hash,approval.source_document_id,approval.source_document_revision,
        policy.snapshot_hash,document.kind,document.state document_state,document.revision document_revision,
        cutover.epoch,cutover.state cutover_state,clock_timestamp() checked_at
        FROM inventory_approval approval JOIN inventory_approval_policy_version policy ON policy.tenant_id=approval.tenant_id AND policy.id=approval.policy_version_id
        JOIN inventory_document document ON document.tenant_id=approval.tenant_id AND document.id=approval.source_document_id
        JOIN inventory_tenant_cutover cutover ON cutover.tenant_id=approval.tenant_id
        WHERE approval.tenant_id=? AND approval.id=?""", sql.tenant, attempt.requestId) { row ->
        when {
            row.getTimestamp("checked_at").toInstant() >= row.getTimestamp("expires_at").toInstant() -> WarehouseApprovalStatus.EXPIRED
            row.getTimestamp("expires_at").toInstant() != guard.expiresAt || row.getString("status") != (if (decided) "APPROVED" else "PENDING") -> WarehouseApprovalStatus.STALE
            row.getLong("revision") != attempt.requestRevision + (if (decided) 1 else 0) -> WarehouseApprovalStatus.STALE
            row.uuid("policy_version_id") != attempt.policyVersionId || row.getString("snapshot_hash") != guard.policyHash ||
                row.getString("policy_snapshot_hash") != guard.policyHash || row.getString("source_snapshot_hash") != guard.sourceHash -> WarehouseApprovalStatus.STALE
            row.uuid("source_document_id") != attempt.sourceDocumentId || row.getLong("source_document_revision") != attempt.sourceRevision ||
                row.getLong("document_revision") != attempt.sourceRevision || row.getString("kind") != "RECEIPT" || row.getString("document_state") != "DRAFT" -> WarehouseApprovalStatus.STALE
            row.getLong("epoch") != guard.cutoverEpoch || row.getString("cutover_state") != "ENFORCED" -> WarehouseApprovalStatus.STALE
            else -> null
        }
    }
    if (states.size != 1) throw ApprovalPostingStopped(guard, WarehouseApprovalStatus.STALE)
    states.single()?.let { throw ApprovalPostingStopped(guard, it) }
}

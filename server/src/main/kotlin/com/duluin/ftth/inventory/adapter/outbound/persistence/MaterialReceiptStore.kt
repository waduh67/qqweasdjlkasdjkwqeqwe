package com.duluin.ftth.inventory.adapter.outbound.persistence

import com.duluin.ftth.inventory.WarehouseErrorCode
import com.duluin.ftth.inventory.application.service.MaterialReceiptSnapshot
import org.springframework.stereotype.Repository
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.util.UUID

@Repository
class MaterialReceiptStore(private val jdbc: WarehouseCommandJdbc) {
    private val mapper = jacksonObjectMapper()

    fun accepted(issue: UUID): Map<UUID, Long> = jdbc.execute { sql ->
        sql.query("""SELECT line.issue_line_id,sum(line.accepted_base::numeric) accepted
            FROM inventory_material_receipt_line line JOIN inventory_material_receipt receipt
            ON receipt.tenant_id=line.tenant_id AND receipt.id=line.receipt_id
            WHERE receipt.tenant_id=? AND receipt.issue_id=? GROUP BY line.issue_line_id""", sql.tenant, issue) {
            it.uuid("issue_line_id") to it.getBigDecimal("accepted").longValueExact()
        }.toMap()
    }

    fun remainingIdentity(line: UUID, dispatched: UUID): UUID = jdbc.execute { sql ->
        val previous = sql.query("""SELECT line.remaining_identity_id FROM inventory_material_receipt_line line
            JOIN inventory_material_receipt receipt ON receipt.tenant_id=line.tenant_id AND receipt.id=line.receipt_id
            WHERE line.tenant_id=? AND line.issue_line_id=? AND line.accepted_base>0
            ORDER BY receipt.issue_revision DESC LIMIT 1""", sql.tenant, line) { it.optionalUuid("remaining_identity_id") }
        if (previous.isEmpty()) dispatched else previous.single() ?: sql.fail(WarehouseErrorCode.INSUFFICIENT_STOCK)
    }

    fun destination(receiver: UUID): UUID = jdbc.execute { sql ->
        sql.query("""SELECT id FROM inventory_location WHERE tenant_id=? AND custodian_id=?
            AND kind='TECHNICIAN' AND state='ACTIVE' ORDER BY id FOR SHARE""", sql.tenant, receiver) { it.uuid("id") }
            .singleOrNull() ?: sql.fail(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
    }

    fun save(snapshot: MaterialReceiptSnapshot, body: String) = jdbc.execute { sql ->
        sql.update("""INSERT INTO inventory_material_receipt(id,tenant_id,issue_id,issue_revision,receiver_id,receiver_name,
            evidence_reference,recorded_at,posting_id,snapshot) VALUES (?,?,?,?,?,?,?,?,?,?)""",
            snapshot.receiptId, sql.tenant, snapshot.issueId, snapshot.revision, snapshot.receiver.id, snapshot.receiver.name,
            snapshot.evidenceReference, snapshot.recordedAt, snapshot.postingId, body)
        snapshot.lines.forEach { line ->
            val selection = line.selection
            sql.update("""INSERT INTO inventory_material_receipt_line(id,tenant_id,receipt_id,issue_id,issue_line_id,dispatched_identity_id,
                source_identity_id,source_revision,accepted_identity_id,remaining_identity_id,accepted_base,missing_base,rejected_base,
                prior_accepted_base,remaining_base,base_unit,reason) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
                UUID.randomUUID(), sql.tenant, snapshot.receiptId, snapshot.issueId, selection.issueLineId, selection.stockIdentityId,
                line.source.stockIdentityId, line.sourceRevision, line.accepted?.stockIdentityId, line.remainder?.stockIdentityId,
                selection.acceptedBase.toLong(), selection.missingBase.toLong(), selection.rejectedBase.toLong(),
                line.priorAcceptedBase.toLong(), line.remainingBase.toLong(), selection.baseUnit, selection.reason)
        }
    }

    fun get(id: UUID): MaterialReceiptSnapshot = jdbc.execute { sql ->
        val body = sql.value("SELECT snapshot FROM inventory_material_receipt WHERE tenant_id=? AND id=?", sql.tenant, id)
            ?: sql.fail(WarehouseErrorCode.NOT_FOUND)
        mapper.readValue(body, MaterialReceiptSnapshot::class.java)
    }

    fun body(id: UUID): String = jdbc.execute { sql ->
        sql.value("SELECT snapshot FROM inventory_material_receipt WHERE tenant_id=? AND id=?", sql.tenant, id)
            ?: sql.fail(WarehouseErrorCode.NOT_FOUND)
    }
}

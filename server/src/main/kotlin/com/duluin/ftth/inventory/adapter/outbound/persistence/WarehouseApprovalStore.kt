package com.duluin.ftth.inventory.adapter.outbound.persistence

import com.duluin.ftth.inventory.*
import com.duluin.ftth.inventory.application.service.WarehouseCanonicalPayload
import org.springframework.stereotype.Repository
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.time.Instant
import java.util.UUID

data class ApprovalReplay(val actor: UUID, val requestId: UUID, val hash: String, val response: WarehouseApprovalResponse)
data class ApprovalSourceState(val revision: Long, val state: String, val kind: String, val requester: UUID,
    val code: String, val disposition: String?, val content: String, val locations: Set<UUID>)

@Repository
class WarehouseApprovalStore(private val jdbc: WarehouseCommandJdbc) {
    private val mapper = jacksonObjectMapper()
    fun source(id: UUID): ApprovalSourceState = jdbc.execute { sql ->
        val header = sql.query("SELECT * FROM inventory_document WHERE tenant_id=? AND id=? FOR UPDATE", sql.tenant, id) {
            ApprovalSourceState(it.getLong("revision"), it.getString("state"), it.getString("kind"), it.uuid("actor_id"),
                it.getString("code"), it.getString("approval_disposition"), "", emptySet())
        }.singleOrNull() ?: sql.fail(WarehouseErrorCode.NOT_FOUND)
        val locations = sql.query("SELECT location_id,destination_location_id FROM inventory_document_line WHERE tenant_id=? AND document_id=? ORDER BY id FOR SHARE",
            sql.tenant, id) { listOfNotNull(it.optionalUuid("location_id"), it.optionalUuid("destination_location_id")) }.flatten().toSet()
        val content = requireNotNull(sql.value("""SELECT jsonb_build_object('document',to_jsonb(document),'lines',
            (SELECT jsonb_agg(to_jsonb(line) ORDER BY line.id) FROM inventory_document_line line WHERE line.tenant_id=document.tenant_id AND line.document_id=document.id),
            'intake',(SELECT to_jsonb(intake) FROM inventory_receipt_intake intake WHERE intake.tenant_id=document.tenant_id AND intake.id=document.id))::text
            FROM inventory_document document WHERE document.tenant_id=? AND document.id=?""", sql.tenant, id))
        return@execute header.copy(content = WarehouseCanonicalPayload.parse(content).json, locations = locations)
    }
    fun get(id: UUID, lock: Boolean = false): WarehouseApprovalRecord = jdbc.execute { sql ->
        sql.query("SELECT * FROM inventory_approval WHERE tenant_id=? AND id=? AND evaluation_snapshot IS NOT NULL${if (lock) " FOR UPDATE" else ""}", sql.tenant, id) {
            WarehouseApprovalRecord(id, mapper.readValue(it.getString("evaluation_snapshot"), WarehouseApprovalSnapshot::class.java),
                WarehouseApprovalStatus.valueOf(it.getString("status")), it.getLong("revision"), it.getTimestamp("requested_at").toInstant(),
                it.getTimestamp("expires_at").toInstant(), it.getString("terminal_body"))
        }.singleOrNull() ?: sql.fail(WarehouseErrorCode.NOT_FOUND)
    }
    fun findSource(id: UUID, revision: Long): UUID? = jdbc.execute { sql ->
        sql.query("SELECT id FROM inventory_approval WHERE tenant_id=? AND source_document_id=? AND source_document_revision=?", sql.tenant, id, revision) { it.uuid("id") }.singleOrNull()
    }
    fun insert(record: WarehouseApprovalRecord, key: String, hash: String) = jdbc.execute { sql ->
        val snapshot = record.snapshot
        val evaluation = snapshot.evaluation
        val policy = requireNotNull(evaluation.policy)
        sql.update("""INSERT INTO inventory_approval(id,tenant_id,approval_type,amount,requester_id,policy_version,policy_snapshot,
            policy_snapshot_hash,operation_key,operation_hash,requested_at,expires_at,status,revision,policy_version_id,source_document_id,
            source_document_revision,source_snapshot_hash,business_action,value_numerator,value_denominator,currency,independence_snapshot,
            authority_epoch,evaluation_snapshot,source_snapshot,location_ids,cutover_epoch)
            VALUES (?,?,?,NULL,?,?,?::jsonb,?,?,?,?,?,'PENDING',0,?,?,?,?,?,?,?,?,?::jsonb,?,?,?,?,?)""",
            record.id, sql.tenant, evaluation.operation.name, snapshot.requesterId, policy.revision, mapper.writeValueAsString(policy),
            WarehouseCanonicalPayload.parse(mapper.writeValueAsString(policy)).hash, key, hash, record.requestedAt, record.expiresAt, policy.id, evaluation.sourceDocumentId,
            evaluation.sourceRevision, snapshot.sourceHash, evaluation.operation.name, requireNotNull(evaluation.valueNumerator).toBigDecimal(),
            requireNotNull(evaluation.valueDenominator).toBigDecimal(), evaluation.currency, mapper.writeValueAsString(evaluation.excludedUserIds),
            evaluation.authorityEpoch, mapper.writeValueAsString(snapshot), snapshot.source,
            sql.connection.createArrayOf("uuid", snapshot.locations.toTypedArray()), snapshot.cutoverEpoch)
    }
    fun requirements(record: WarehouseApprovalRecord) = jdbc.execute { sql ->
        record.snapshot.evaluation.tiers.forEach { tier ->
            sql.update("INSERT INTO inventory_approval_requirement(id,tenant_id,approval_id,tier,candidates,requirement) VALUES (?,?,?,?,?::jsonb,?::jsonb)",
                UUID.randomUUID(), sql.tenant, record.id, tier.number, mapper.writeValueAsString(tier.approvers), mapper.writeValueAsString(tier))
        }
    }
    fun decisions(id: UUID): List<WarehouseApprovalDecisionRecord> = jdbc.execute { sql ->
        sql.query("SELECT independence_snapshot FROM inventory_approval_decision WHERE tenant_id=? AND approval_id=? ORDER BY revision", sql.tenant, id) {
            mapper.readValue(it.getString(1), WarehouseApprovalDecisionRecord::class.java)
        }
    }
    fun decision(record: WarehouseApprovalRecord, decision: WarehouseApprovalDecisionRecord, key: String, hash: String) = jdbc.execute { sql ->
        sql.update("""INSERT INTO inventory_approval_decision(id,tenant_id,approval_id,tier,approver_id,delegated_from,decision,reason,decided_at,
            revision,operation_key,operation_hash,delegation_id,policy_version_id,authority_epoch,independence_snapshot,evidence_reference)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?::jsonb,?)""", decision.id, sql.tenant, record.id, decision.tier, decision.actorId,
            decision.delegation?.approverId, decision.decision, decision.reason, decision.decidedAt, decision.revision, key, hash,
            decision.delegation?.id, requireNotNull(record.snapshot.evaluation.policy).id, decision.authorityEpoch,
            mapper.writeValueAsString(decision), decision.evidenceReference)
    }
    fun advance(record: WarehouseApprovalRecord, status: WarehouseApprovalStatus, body: String?) = jdbc.execute { sql ->
        check(sql.update("UPDATE inventory_approval SET status=?,revision=revision+1,terminal_body=?,updated_at=clock_timestamp() WHERE tenant_id=? AND id=? AND revision=? AND status='PENDING'",
            status, body, sql.tenant, record.id, record.revision) == 1)
    }
    fun replay(namespace: String, key: String): ApprovalReplay? = jdbc.execute { sql ->
        sql.value("SELECT pg_advisory_xact_lock(hashtextextended(?,0))", "${sql.tenant}|$namespace|$key")
        sql.query("SELECT * FROM inventory_approval_command WHERE tenant_id=? AND namespace=? AND operation_key=?", sql.tenant, namespace, key) {
            ApprovalReplay(it.uuid("actor_id"), it.uuid("approval_id"), it.getString("payload_hash"),
                WarehouseApprovalResponse(it.getInt("original_status"), it.getString("original_body")))
        }.singleOrNull()
    }
    fun response(namespace: String, key: String, actor: UUID, request: UUID, hash: String, response: WarehouseApprovalResponse) = jdbc.execute { sql ->
        sql.update("INSERT INTO inventory_approval_command(id,tenant_id,namespace,operation_key,actor_id,approval_id,payload_hash,original_status,original_body) VALUES (?,?,?,?,?,?,?,?,?)",
            UUID.randomUUID(), sql.tenant, namespace, key, actor, request, hash, response.status, response.body)
    }
    fun effect(record: WarehouseApprovalRecord, operation: UUID, body: String, event: UUID, now: Instant) = jdbc.execute { sql ->
        sql.update("""INSERT INTO inventory_approval_effect(id,tenant_id,approval_id,approval_type,status,operation_key,emitted_at,
            source_document_id,source_document_revision,posting_operation_id,original_body,event_id) VALUES (?,?,?,?,'APPROVED',?,?,?,?,?,?,?)""",
            UUID.randomUUID(), sql.tenant, record.id, record.snapshot.evaluation.operation.name, record.id.toString(), now,
            record.snapshot.evaluation.sourceDocumentId, record.snapshot.evaluation.sourceRevision, operation, body, event)
    }
    fun event(operation: UUID): UUID = jdbc.execute { sql ->
        sql.query("SELECT id FROM inventory_outbox WHERE tenant_id=? AND operation_id=? AND event_kind='RECEIVED'", sql.tenant, operation) { it.uuid("id") }.single()
    }
    fun candidates(): List<UUID> = jdbc.execute { sql ->
        sql.query("SELECT id FROM inventory_approval WHERE tenant_id=? AND evaluation_snapshot IS NOT NULL ORDER BY requested_at DESC,id", sql.tenant) { it.uuid("id") }
    }
    fun due(): UUID? = jdbc.execute { sql ->
        sql.query("""SELECT document.id FROM inventory_document document JOIN inventory_approval approval
            ON approval.tenant_id=document.tenant_id AND approval.source_document_id=document.id
            WHERE approval.tenant_id=? AND approval.status='PENDING' AND approval.expires_at<=clock_timestamp()
            AND approval.evaluation_snapshot IS NOT NULL ORDER BY document.id LIMIT 1 FOR UPDATE OF document SKIP LOCKED""", sql.tenant) { it.uuid("id") }.singleOrNull()
    }
    fun dueForSource(id: UUID): List<UUID> = jdbc.execute { sql ->
        sql.query("SELECT id FROM inventory_approval WHERE tenant_id=? AND source_document_id=? AND status='PENDING' AND expires_at<=clock_timestamp() ORDER BY id FOR UPDATE",
            sql.tenant, id) { it.uuid("id") }
    }
    fun reworkDisposition(id: UUID, revision: Long, required: Boolean) = jdbc.execute { sql ->
        if (sql.update("UPDATE inventory_document SET approval_disposition=?,revision=revision+1,updated_at=clock_timestamp() WHERE tenant_id=? AND id=? AND revision=? AND state='DRAFT'",
            if (required) "REWORK_REQUIRED" else null, sql.tenant, id, revision) != 1) sql.fail(WarehouseErrorCode.STALE_REVISION)
    }
    fun evidence(id: UUID, document: UUID) = jdbc.execute { sql ->
        if (sql.value("SELECT id FROM inventory_receipt_evidence WHERE tenant_id=? AND id=? AND document_id=?", sql.tenant, id, document) == null)
            sql.fail(WarehouseErrorCode.NOT_FOUND)
    }
}

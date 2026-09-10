package com.duluin.ftth.inventory.application.service

import com.duluin.ftth.iam.CurrentAuthority
import com.duluin.ftth.iam.CurrentAuthorityApi
import com.duluin.ftth.inventory.*
import com.duluin.ftth.inventory.adapter.outbound.persistence.*
import com.duluin.ftth.inventory.application.port.inbound.masterFailure
import com.duluin.ftth.inventory.application.port.outbound.PostingOperation
import com.duluin.ftth.inventory.application.port.outbound.WarehouseMasterStore
import com.duluin.ftth.inventory.domain.model.InventoryApprovalDecision
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.util.UUID

@Service
class DurableApprovalService(private val cutovers: InventoryTenantCutoverApi, private val authority: CurrentAuthorityApi,
    private val policy: WarehousePolicyEvaluationApi, private val clock: WarehousePolicyPersistence,
    private val store: WarehouseApprovalStore, private val access: WarehousePolicyAccess,
    private val eligibility: WarehouseApprovalAuthority, private val masters: WarehouseMasterStore,
    private val inbox: WarehouseInboxApi, private val owners: List<WarehouseApprovalOwner>, private val probes: List<WarehouseApprovalProbe>) {
    private val mapper = jacksonObjectMapper()

    @Transactional(rollbackFor = [Exception::class])
    fun request(input: WarehouseSourceInput, key: String): WarehouseApprovalResponse {
        receiptKey(key)
        if (input.sourceRevision < 0) masterFailure(WarehouseErrorCode.MALFORMED_REQUEST)
        val cutover = cutovers.lockForCommand(cutovers.read().epoch, WarehouseOperationClass.ORDINARY_STOCK)
        val current = authority.lockCurrent()
        access.permission(current, "inventory.approval.request")
        access.permission(current, "inventory.approval.view")
        masters.lockTopology()
        val source = store.source(input.sourceDocumentId)
        source.locations.forEach { access.location(it, current) }
        val hash = hash(input)
        replay("request", key, hash, current)?.let { return it }
        if (source.revision != input.sourceRevision) masterFailure(WarehouseErrorCode.STALE_REVISION)
        if (store.findSource(input.sourceDocumentId, input.sourceRevision) != null) masterFailure(WarehouseErrorCode.IDEMPOTENCY_CONFLICT)
        owner(source.kind).validate(source, input.sourceDocumentId)
        if (source.requester != current.fence.identity.userId) masterFailure(WarehouseErrorCode.FORBIDDEN)
        val evaluation = policy.evaluate(input)
        if (evaluation.tiers.isEmpty()) masterFailure(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
        if (!independentAssignment(evaluation.tiers, emptySet())) masterFailure(WarehouseErrorCode.INDEPENDENT_APPROVER_REQUIRED)
        val snapshot = WarehouseApprovalSnapshot(evaluation, source.content, WarehouseCanonicalPayload.parse(source.content).hash,
            source.locations, source.requester, source.code, cutover.snapshot.epoch)
        val now = clock.now()
        val record = WarehouseApprovalRecord(UUID.randomUUID(), snapshot, WarehouseApprovalStatus.PENDING, 0, now,
            now.plusSeconds(requireNotNull(evaluation.policy).expiryHours.toLong() * 3600), null)
        store.insert(record, key, hash)
        probe(WarehouseApprovalStage.REQUEST, record.id)
        store.requirements(record)
        probe(WarehouseApprovalStage.REQUIREMENTS, record.id)
        val response = WarehouseApprovalResponse(201, body(record))
        recordResponse("request", key, hash, current, record.id, response)
        return response
    }

    @Transactional(rollbackFor = [Exception::class])
    fun decide(input: WarehouseApprovalDecisionInput, key: String): WarehouseApprovalResponse {
        receiptKey(key)
        if (input.expectedRevision < 0 || (input.reason?.length ?: 0) > 500 ||
            (input.decision == InventoryApprovalDecision.REJECT && input.reason.isNullOrBlank())) masterFailure(WarehouseErrorCode.MALFORMED_REQUEST)
        val cutover = cutovers.lockForCommand(cutovers.read().epoch, WarehouseOperationClass.CONTROL_PLANE)
        val current = authority.lockCurrent()
        access.permission(current, "inventory.approval.decide")
        masters.lockTopology()
        val preview = store.get(input.requestId)
        eligibility.view(preview, current)
        val source = store.source(preview.snapshot.evaluation.sourceDocumentId)
        val record = store.get(input.requestId, true)
        val hash = hash(input)
        replay("decide", key, hash, current)?.let { return it }
        val decisions = store.decisions(record.id)
        val tier = record.snapshot.evaluation.tiers.getOrNull(decisions.size)?.number ?: record.snapshot.evaluation.tiers.last().number
        val now = clock.now()
        val delegation = eligibility.authorize(record, tier, current, if (record.status == WarehouseApprovalStatus.PENDING) decisions else emptyList(), now)
        val attempt = WarehouseApprovalAttempt(record.id, record.snapshot.evaluation.sourceDocumentId, record.snapshot.evaluation.sourceRevision,
            requireNotNull(record.snapshot.evaluation.policy).id, record.revision, tier, input.decision, delegation)
        if (record.status != WarehouseApprovalStatus.PENDING || record.revision != input.expectedRevision) {
            val response = WarehouseApprovalResponse(409, mapper.writeValueAsString(view(record).copy(code = "STALE_REVISION")))
            recordResponse("decide", key, hash, current, record.id, response, attempt)
            return response
        }
        val invalid = when {
            now >= record.expiresAt -> WarehouseApprovalStatus.EXPIRED
            cutover.snapshot.epoch != record.snapshot.cutoverEpoch || cutover.snapshot.state != WarehouseCutoverState.ENFORCED -> WarehouseApprovalStatus.STALE
            source.revision != record.snapshot.evaluation.sourceRevision || WarehouseCanonicalPayload.parse(source.content).hash != record.snapshot.sourceHash -> WarehouseApprovalStatus.STALE
            else -> null
        }
        if (invalid != null) {
            val response = terminate(record, invalid)
            recordResponse("decide", key, hash, current, record.id, response, attempt)
            return response
        }
        owner(source.kind).validate(source, record.snapshot.evaluation.sourceDocumentId)
        input.evidenceReference?.let { store.evidence(it, record.snapshot.evaluation.sourceDocumentId) }
        val decision = WarehouseApprovalDecisionRecord(UUID.randomUUID(), tier, current.fence.identity.userId, input.decision,
            input.reason, now, record.revision + 1, delegation, current.fence.epoch, input.evidenceReference)
        store.decision(record, decision, key, hash)
        probe(WarehouseApprovalStage.DECISION, record.id)
        val final = input.decision == InventoryApprovalDecision.APPROVE && decisions.size + 1 == record.snapshot.evaluation.tiers.size
        val operationId = if (final) UUID.randomUUID() else null
        val status = when {
            input.decision == InventoryApprovalDecision.REJECT -> WarehouseApprovalStatus.REWORK_REQUIRED
            final -> WarehouseApprovalStatus.APPROVED
            else -> WarehouseApprovalStatus.PENDING
        }
        val result = body(record.copy(status = status, revision = record.revision + 1), operationId)
        store.advance(record, status, if (status == WarehouseApprovalStatus.PENDING) null else result)
        if (input.decision == InventoryApprovalDecision.REJECT) store.reworkDisposition(record.snapshot.evaluation.sourceDocumentId, source.revision, true)
        if (final) {
            if (clock.now() >= record.expiresAt) masterFailure(WarehouseErrorCode.APPROVAL_REQUIRED)
            val operation = PostingOperation(requireNotNull(operationId), "warehouse.approval.effect", record.id.toString(), current.fence.identity.userId,
                record.snapshot.evaluation.sourceDocumentId, "approval:${record.id}", record.snapshot.sourceHash, "RECEIVE", 200, result, current.fence.epoch)
            owner(source.kind).apply(record, operation, current, cutover)
            probe(WarehouseApprovalStage.OWNER_EFFECT, record.id)
            val event = store.event(operation.id)
            check(inbox.consume(event, "warehouse.approval.receipt") { })
            probe(WarehouseApprovalStage.INBOX, record.id)
            store.effect(record, operation.id, result, event, now)
            probe(WarehouseApprovalStage.EFFECT_RECEIPT, record.id)
        }
        val response = WarehouseApprovalResponse(200, result)
        recordResponse("decide", key, hash, current, record.id, response, attempt)
        return response
    }

    @Transactional(rollbackFor = [Exception::class])
    fun rework(input: WarehouseApprovalReworkInput, key: String): WarehouseApprovalResponse {
        receiptKey(key)
        cutovers.lockForCommand(cutovers.read().epoch, WarehouseOperationClass.ORDINARY_STOCK)
        val current = authority.lockCurrent()
        access.permission(current, "inventory.approval.request")
        masters.lockTopology()
        val record = store.get(input.requestId)
        eligibility.view(record, current)
        val source = store.source(record.snapshot.evaluation.sourceDocumentId)
        if (record.snapshot.requesterId != current.fence.identity.userId) masterFailure(WarehouseErrorCode.FORBIDDEN)
        val hash = hash(input)
        replay("rework", key, hash, current)?.let { return it }
        if (record.status != WarehouseApprovalStatus.REWORK_REQUIRED || source.disposition != "REWORK_REQUIRED") masterFailure(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
        if (source.revision != input.expectedRevision) masterFailure(WarehouseErrorCode.STALE_REVISION)
        store.reworkDisposition(record.snapshot.evaluation.sourceDocumentId, source.revision, false)
        val response = WarehouseApprovalResponse(200, mapper.writeValueAsString(mapOf("requestId" to record.id,
            "sourceDocumentId" to record.snapshot.evaluation.sourceDocumentId, "sourceRevision" to source.revision + 1, "status" to "DRAFT")))
        recordResponse("rework", key, hash, current, record.id, response)
        return response
    }

    @Transactional(rollbackFor = [Exception::class])
    fun get(id: UUID): WarehouseApprovalView {
        cutovers.lockForCommand(cutovers.read().epoch, WarehouseOperationClass.CONTROL_PLANE)
        val current = authority.lockCurrent()
        val record = store.get(id)
        eligibility.view(record, current)
        store.source(record.snapshot.evaluation.sourceDocumentId)
        val locked = store.get(id, true)
        if (locked.status == WarehouseApprovalStatus.PENDING && clock.now() >= locked.expiresAt) terminate(locked, WarehouseApprovalStatus.EXPIRED)
        return view(store.get(id))
    }
    @Transactional(rollbackFor = [Exception::class])
    fun list(page: Int, size: Int, status: WarehouseApprovalStatus?): WarehousePage<WarehouseApprovalView> {
        if (page < 0 || size !in 1..100) masterFailure(WarehouseErrorCode.MALFORMED_REQUEST)
        cutovers.lockForCommand(cutovers.read().epoch, WarehouseOperationClass.CONTROL_PLANE)
        val current = authority.lockCurrent()
        access.permission(current, "inventory.approval.view")
        val visible = store.candidates().mapNotNull { id ->
            try { eligibility.view(store.get(id), current); view(store.get(id)) } catch (failure: WarehouseContractException) {
                if (failure.error.code == WarehouseErrorCode.NOT_FOUND) null else throw failure
            }
        }.filter { status == null || it.status == status }
        val offset = page.toLong() * size
        return WarehousePage(if (offset >= visible.size) emptyList() else visible.drop(offset.toInt()).take(size), page, size, visible.size.toLong())
    }
    @Transactional(rollbackFor = [Exception::class])
    fun history(id: UUID): List<WarehouseApprovalDecisionRecord> { get(id); return store.decisions(id) }

    private fun replay(namespace: String, key: String, hash: String, current: CurrentAuthority): WarehouseApprovalResponse? {
        val replay = store.replay(namespace, key) ?: return null
        if (replay.actor != current.fence.identity.userId) masterFailure(WarehouseErrorCode.FORBIDDEN)
        val record = store.get(replay.requestId)
        eligibility.view(record, current)
        if (namespace == "decide") {
            val tier = replay.attempt?.tier ?: record.snapshot.evaluation.tiers
                .getOrNull(mapper.readTree(replay.response.body).path("revision").asInt() - 1)?.number
                ?: masterFailure(WarehouseErrorCode.FORBIDDEN)
            val delegation = eligibility.authorize(record, tier, current, emptyList(), clock.now())
            if (replay.attempt?.delegation != null && delegation?.id != replay.attempt.delegation.id) masterFailure(WarehouseErrorCode.FORBIDDEN)
        }
        if (replay.hash != hash) masterFailure(WarehouseErrorCode.IDEMPOTENCY_CONFLICT)
        return replay.response
    }
    private fun recordResponse(namespace: String, key: String, hash: String, current: CurrentAuthority, id: UUID, response: WarehouseApprovalResponse,
        attempt: WarehouseApprovalAttempt? = null) {
        store.response(namespace, key, current.fence.identity.userId, id, hash, response, attempt)
        probe(WarehouseApprovalStage.RESPONSE, id)
    }
    internal fun terminate(record: WarehouseApprovalRecord, status: WarehouseApprovalStatus): WarehouseApprovalResponse {
        val result = body(record.copy(status = status, revision = record.revision + 1))
        store.advance(record, status, result)
        return WarehouseApprovalResponse(409, result)
    }
    private fun view(record: WarehouseApprovalRecord): WarehouseApprovalView = record.terminalBody?.let {
        mapper.readValue(it, WarehouseApprovalView::class.java)
    } ?: WarehouseApprovalView(record.id, record.snapshot.evaluation.sourceDocumentId, record.snapshot.evaluation.sourceRevision,
        record.status, record.revision, record.expiresAt, when (record.status) {
            WarehouseApprovalStatus.STALE -> "STALE_REVISION"
            WarehouseApprovalStatus.EXPIRED -> "APPROVAL_EXPIRED"
            else -> record.status.name
        })
    private fun body(record: WarehouseApprovalRecord, operation: UUID? = null): String = mapper.writeValueAsString(view(record).copy(effectOperationId = operation))
    private fun hash(input: Any): String = WarehouseCanonicalPayload.parse(mapper.writeValueAsString(input)).hash
    private fun independentAssignment(tiers: List<PolicyTierRequirement>, used: Set<UUID>): Boolean {
        if (tiers.isEmpty()) return true
        return tiers.first().approvers.any { candidate ->
            val identities = setOfNotNull(candidate.userId, candidate.delegatedFrom)
            identities.none { it in used } && independentAssignment(tiers.drop(1), used + identities)
        }
    }
    private fun owner(kind: String): WarehouseApprovalOwner = owners.singleOrNull { it.kind == kind }
        ?: masterFailure(WarehouseErrorCode.SOURCE_NOT_VERIFIED, "The source document owner does not yet support approval execution")
    private fun probe(stage: WarehouseApprovalStage, id: UUID) { probes.forEach { it.reached(stage, id) } }
}

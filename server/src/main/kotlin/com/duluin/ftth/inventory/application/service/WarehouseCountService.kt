package com.duluin.ftth.inventory.application.service

import com.duluin.ftth.iam.CurrentAuthority
import com.duluin.ftth.iam.CurrentAuthorityApi
import com.duluin.ftth.inventory.*
import com.duluin.ftth.inventory.adapter.outbound.persistence.*
import com.duluin.ftth.inventory.application.port.inbound.masterFailure
import com.duluin.ftth.inventory.application.port.outbound.WarehouseMasterStore
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.util.UUID

@Service
@Transactional(rollbackFor = [Exception::class])
class WarehouseCountService(private val cutovers: InventoryTenantCutoverApi, private val authority: CurrentAuthorityApi,
    private val access: WarehousePolicyAccess, private val masters: WarehouseMasterStore,
    private val store: WarehouseCountStore, private val receipts: WarehouseCountReceipts, private val operations: WarehouseOperationStore,
    private val query: WarehouseCountQuery, private val counters: WarehouseCountCounters,
    private val lifetime: WarehouseDraftLifetimeStore) : InventoryCountApi {
    private val mapper = jacksonObjectMapper()

    override fun create(input: WarehouseCountDraft, key: String): WarehouseOperationReceipt {
        validateKey(key)
        validateDraft(input)
        val cutover = cutovers.lockForCommand(cutovers.read().epoch, WarehouseOperationClass.ORDINARY_STOCK)
        val current = authority.lockCurrent()
        access.permission(current, "inventory.count.manage")
        access.permission(current, "inventory.count.view")
        masters.lockTopology()
        access.location(input.locationId, current)
        val canonical = WarehouseCanonicalPayload.parse(mapper.writeValueAsString(input))
        replay("create", key, canonical.hash, current, cutover.snapshot.epoch)?.let { return it }
        val positions = positions(input, current)
        val id = UUID.randomUUID()
        store.create(id, input, current.fence.identity.userId, current.fence.epoch, cutover.snapshot.epoch, positions)
        return receipts.record(id, 0, "create", key, canonical.hash, canonical.json, current, cutover.snapshot.epoch, 201,
            mapper.writeValueAsString(store.get(id).view))
    }

    override fun update(id: UUID, input: WarehouseCountUpdate, key: String): WarehouseOperationReceipt {
        validateDraft(input.draft)
        return mutate(id, input, input.expectedRevision, "update", key) { session, current ->
            requester(session, current)
            lifetime.assertDocumentLive(id)
            state(session, WarehouseCountState.DRAFT)
            if (session.view.roundRevision != null || store.facts(id).isNotEmpty()) masterFailure(WarehouseErrorCode.STALE_REVISION)
            val positions = positions(input.draft, current)
            store.replaceDraft(id, input.expectedRevision, input.draft, positions)
            null
        }
    }

    override fun start(id: UUID, input: WarehouseCountRevision, key: String) = mutate(id, input, input.expectedRevision, "start", key) { session, current ->
        requester(session, current)
        lifetime.assertDocumentLive(id)
        state(session, WarehouseCountState.DRAFT)
        store.advance(id, input.expectedRevision, WarehouseCountState.COUNTING)
        store.startRound(id, input.expectedRevision + 1)
        null
    }

    override fun observe(id: UUID, input: WarehouseCountObservation, key: String): WarehouseOperationReceipt {
        if (!input.quantityBase.matches(Regex("[0-9]+")) || input.quantityBase.toLongOrNull() == null ||
            input.reason.isBlank() || input.reason.length > 500 || input.documentReference.isBlank() || input.documentReference.length > 500)
            masterFailure(WarehouseErrorCode.MALFORMED_REQUEST)
        return mutate(id, input, input.expectedRevision, "observe", key) { session, current ->
            state(session, WarehouseCountState.COUNTING)
            val entry = session.view.entries.singleOrNull { it.balanceId == input.balanceId }
                ?: masterFailure(WarehouseErrorCode.NOT_FOUND)
            if (entry.counterId != current.fence.identity.userId) masterFailure(WarehouseErrorCode.FORBIDDEN)
            if (store.facts(id).any { it.roundRevision == session.view.roundRevision && it.balanceId == input.balanceId })
                masterFailure(WarehouseErrorCode.STALE_REVISION, "An observation is immutable; request a recount")
            val position = store.position(input.balanceId)
            val canonical = WarehouseCanonicalPayload.parse(mapper.writeValueAsString(input))
            store.observe(session, input, position, current.fence.identity.userId, key, canonical.hash)
            store.advance(id, input.expectedRevision, WarehouseCountState.COUNTING)
            null
        }
    }

    override fun submit(id: UUID, input: WarehouseCountRevision, key: String) = mutate(id, input, input.expectedRevision, "submit", key) { session, current ->
        requester(session, current)
        state(session, WarehouseCountState.COUNTING)
        if (store.facts(id).count { it.roundRevision == session.view.roundRevision } != session.view.entries.size)
            masterFailure(WarehouseErrorCode.SOURCE_NOT_VERIFIED, "Every assigned dimension requires an observation")
        if (store.stale(session)) {
            store.advance(id, input.expectedRevision, WarehouseCountState.RECOUNT_REQUIRED)
            WarehouseErrorCode.COUNT_STALE
        } else {
            store.advance(id, input.expectedRevision, WarehouseCountState.SUBMITTED)
            if (store.unchanged(session)) {
                store.advance(id, input.expectedRevision + 1, WarehouseCountState.APPROVED)
                store.advance(id, input.expectedRevision + 2, WarehouseCountState.POSTED)
            }
            null
        }
    }

    override fun recount(id: UUID, input: WarehouseCountRevision, key: String) = mutate(id, input, input.expectedRevision, "recount", key) { session, current ->
        requester(session, current)
        state(session, WarehouseCountState.RECOUNT_REQUIRED)
        store.advance(id, input.expectedRevision, WarehouseCountState.COUNTING)
        store.startRound(id, input.expectedRevision + 1)
        null
    }

    override fun get(id: UUID): WarehouseCountView {
        cutovers.lockForCommand(cutovers.read().epoch, WarehouseOperationClass.CONTROL_PLANE)
        val current = authority.lockCurrent()
        masters.lockTopology()
        val session = store.get(id)
        authorize(session, current)
        return currentView(session.view)
    }

    override fun history(id: UUID, page: WarehousePageRequest): List<WarehouseCountFact> {
        validateCountFilter(WarehouseCountFilter(page.page, page.size))
        get(id)
        val current = authority.lockCurrent()
        val session = store.get(id)
        val counter = current.fence.identity.userId.takeUnless { it == session.requester }
        return query.history(id, page, counter, latestFirst = false).items.map { it.fact }
    }

    override fun review(id: UUID): WarehouseCountReview {
        cutovers.lockForCommand(cutovers.read().epoch, WarehouseOperationClass.CONTROL_PLANE)
        val current = authority.lockCurrent()
        access.permission(current, "inventory.approval.view")
        masters.lockTopology()
        val session = store.get(id)
        access.location(session.view.locationId, current)
        if (session.view.state !in setOf(WarehouseCountState.SUBMITTED, WarehouseCountState.APPROVED, WarehouseCountState.POSTED))
            masterFailure(WarehouseErrorCode.SOURCE_NOT_VERIFIED, "Submit the blind count before reviewing book quantities")
        return store.review(session)
    }

    override fun list(page: Int, size: Int): WarehousePage<WarehouseCountView> {
        if (page < 0 || size !in 1..100) masterFailure(WarehouseErrorCode.MALFORMED_REQUEST)
        cutovers.lockForCommand(cutovers.read().epoch, WarehouseOperationClass.CONTROL_PLANE)
        val current = authority.lockCurrent()
        access.permission(current, "inventory.count.view")
        masters.lockTopology()
        val visible = store.candidates().mapNotNull { id ->
            try { currentView(store.get(id).also { authorize(it, current) }.view) }
            catch (failure: WarehouseContractException) { if (failure.error.code == WarehouseErrorCode.NOT_FOUND) null else throw failure }
        }
        val offset = page.toLong() * size
        return WarehousePage(if (offset >= visible.size) emptyList() else visible.drop(offset.toInt()).take(size), page, size, visible.size.toLong())
    }

    private fun <T : Any> mutate(id: UUID, input: T, revision: Long, action: String, key: String,
        change: (CountSession, CurrentAuthority) -> WarehouseErrorCode?): WarehouseOperationReceipt {
        validateKey(key)
        if (revision < 0 || revision > Long.MAX_VALUE - 3) masterFailure(WarehouseErrorCode.MALFORMED_REQUEST)
        val cutover = cutovers.lockForCommand(cutovers.read().epoch, WarehouseOperationClass.ORDINARY_STOCK)
        val current = authority.lockCurrent()
        access.permission(current, "inventory.count.manage")
        masters.lockTopology()
        val session = store.get(id)
        authorize(session, current)
        val canonical = WarehouseCanonicalPayload.parse(mapper.writeValueAsString(mapOf("id" to id, "input" to input)))
        replay(action, key, canonical.hash, current, cutover.snapshot.epoch)?.let { return it }
        if (session.cutoverEpoch != cutover.snapshot.epoch) masterFailure(WarehouseErrorCode.STALE_CUTOVER)
        if (session.view.revision != revision) masterFailure(WarehouseErrorCode.STALE_REVISION)
        val error = change(session, current)
        val result = store.get(id).view
        val body = if (error == null) mapper.writeValueAsString(result)
            else mapper.writeValueAsString(WarehouseError(error, "Stock changed after observation; perform a recount"))
        val receipt = receipts.record(id, result.revision, action, key, canonical.hash, canonical.json, current, cutover.snapshot.epoch,
            if (error == null) 200 else error.httpStatus, body)
        if (result.state == WarehouseCountState.POSTED) store.complete(session, revision, receipt.operationId, null)
        return receipt
    }

    private fun currentView(view: WarehouseCountView): WarehouseCountView {
        val expiry = lifetime.document(view.id)
        return view.copy(state = if (expiry != null) WarehouseCountState.EXPIRED else view.state, draftExpiry = expiry)
    }

    private fun replay(action: String, key: String, hash: String, current: CurrentAuthority, epoch: Long): WarehouseOperationReceipt? {
        val prior = operations.lockKey("warehouse.count.$action", key) ?: return null
        authorize(store.get(prior.resourceId), current)
        if (prior.actorId != current.fence.identity.userId) masterFailure(WarehouseErrorCode.FORBIDDEN)
        if (prior.hash != hash) masterFailure(WarehouseErrorCode.IDEMPOTENCY_CONFLICT)
        if (prior.cutoverEpoch != epoch) masterFailure(WarehouseErrorCode.STALE_CUTOVER)
        if (action in setOf("create", "update")) {
            val original = mapper.readValue(prior.receipt.originalBody, WarehouseCountView::class.java)
            access.location(original.locationId, current)
        }
        return prior.receipt
    }

    private fun validateDraft(input: WarehouseCountDraft) {
        if (!input.partialLocation || input.reason.isBlank() || input.reason.length > 500 || input.entries.size !in 1..100 ||
            input.entries.map { it.balanceId }.distinct().size != input.entries.size) masterFailure(WarehouseErrorCode.MALFORMED_REQUEST)
    }
    private fun positions(input: WarehouseCountDraft, current: CurrentAuthority): Map<UUID, CountPosition> {
        access.location(input.locationId, current)
        val eligible = counters.eligible(input.locationId, current).map { it.id }.toSet()
        if (input.entries.any { it.counterId !in eligible })
            masterFailure(WarehouseErrorCode.FORBIDDEN, "Assign an active counter with count permission and location access")
        return input.entries.sortedBy { it.balanceId.toString() }.associate { entry ->
            val position = store.position(entry.balanceId)
            if (position.dimension.locationId != input.locationId) masterFailure(WarehouseErrorCode.NOT_FOUND)
            entry.balanceId to position
        }
    }

    private fun authorize(session: CountSession, current: CurrentAuthority) {
        access.permission(current, "inventory.count.view")
        access.location(session.view.locationId, current)
        if (session.requester != current.fence.identity.userId && session.view.entries.none { it.counterId == current.fence.identity.userId })
            masterFailure(WarehouseErrorCode.NOT_FOUND)
    }
    private fun requester(session: CountSession, current: CurrentAuthority) {
        if (session.requester != current.fence.identity.userId) masterFailure(WarehouseErrorCode.FORBIDDEN)
    }
    private fun state(session: CountSession, expected: WarehouseCountState) {
        if (session.view.state != expected) masterFailure(WarehouseErrorCode.STALE_REVISION)
    }
    private fun validateKey(key: String) {
        if (key.isBlank() || key.length > 240) masterFailure(WarehouseErrorCode.MALFORMED_REQUEST)
    }
}

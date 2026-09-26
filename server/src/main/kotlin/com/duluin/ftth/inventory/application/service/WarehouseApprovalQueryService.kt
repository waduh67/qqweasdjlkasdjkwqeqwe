package com.duluin.ftth.inventory.application.service

import com.duluin.ftth.common.security.AuthorityScope
import com.duluin.ftth.iam.CurrentAuthority
import com.duluin.ftth.iam.CurrentAuthorityApi
import com.duluin.ftth.inventory.*
import com.duluin.ftth.inventory.adapter.outbound.persistence.*
import com.duluin.ftth.inventory.application.port.inbound.masterFailure
import com.duluin.ftth.inventory.application.port.outbound.WarehouseMasterStore
import com.duluin.ftth.network.SiteReferenceApi
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration
import java.util.UUID
import tools.jackson.module.kotlin.jacksonObjectMapper

@Service
class WarehouseApprovalQueryService(private val query: WarehouseApprovalQuery, private val projection: WarehouseApprovalProjection,
    private val store: WarehouseApprovalStore, private val approvals: DurableApprovalService, private val evidence: WarehouseApprovalEvidence,
    private val cutovers: InventoryTenantCutoverApi, private val authority: CurrentAuthorityApi, private val access: WarehousePolicyAccess,
    private val eligibility: WarehouseApprovalAuthority, private val masters: WarehouseMasterStore,
    private val sourceLocks: List<WarehouseApprovalSourceLock>, private val owners: List<WarehouseApprovalOwner>,
    private val policy: WarehousePolicyPersistence, private val scopes: InventoryWarehouseScopeApi, private val sites: SiteReferenceApi,
    transactions: PlatformTransactionManager) : InventoryApprovalQueryApi {
    private val transaction = TransactionTemplate(transactions).apply { timeout = 30 }

    override fun list(filter: WarehouseApprovalFilter): WarehousePage<WarehouseApprovalSummary> {
        validateApprovalFilter(filter)
        val selected = mutableListOf<WarehouseApprovalSummary>()
        val offset = filter.page.toLong() * filter.size
        var total = 0L
        var cursor: ApprovalQueryCursor? = null
        do {
            val batch = ownTransaction {
                val current = current()
                masters.lockTopology()
                val areas = if (current.platformAdmin) AuthorityScope.Unrestricted else current.areaScope
                query.candidates(filter, WarehouseQueryAccess(scopes.currentUnderFence(current.fence), areas, sites.visibleAreas(areas), false, false), cursor)
            }
            for (candidate in batch) {
                // Catch OUTSIDE the transaction: a public WO port denial can mark its transaction rollback-only.
                // Only the requested page is projected/retained; candidate batches and memory stay bounded.
                val visible = try { ownTransaction {
                    val locked = locked(candidate.id)
                    val record = locked.record
                    if (filter.status != null && record.status != filter.status) false
                    else {
                        if (total >= offset && selected.size < filter.size) {
                            val names = projection.people(setOf(record.snapshot.requesterId))
                            selected += WarehouseApprovalSummary(ApprovalOutcomeCodec.view(record), record.snapshot.code, record.snapshot.evaluation.operation,
                                projection.person(record.snapshot.requesterId, names), record.requestedAt)
                        }
                        true
                    }
                } } catch (failure: WarehouseContractException) {
                    if (failure.error.code !in setOf(WarehouseErrorCode.NOT_FOUND, WarehouseErrorCode.FORBIDDEN, WarehouseErrorCode.WRONG_CUSTODIAN)) throw failure
                    false
                }
                if (visible) total++
            }
            cursor = batch.lastOrNull()
        } while (batch.size == 100)
        // A role revocation cannot silently become a successful empty queue.
        ownTransaction { current(); true }
        return WarehousePage(selected, filter.page, filter.size, total)
    }

    override fun source(id: UUID): WarehouseApprovalSourceView = ownTransaction {
        val current = current()
        sourceLocks.forEach { it.lock(id, current) }
        masters.lockTopology()
        val source = store.source(id)
        source.locations.forEach { access.location(it, current) }
        val owner = owners.singleOrNull { it.kind == source.kind } ?: masterFailure(WarehouseErrorCode.NOT_FOUND)
        val block = when {
            cutovers.read().state != (if (source.kind == "OPENING_BALANCE") WarehouseCutoverState.VALIDATING else WarehouseCutoverState.ENFORCED) -> "CUTOVER_REQUIRED"
            source.requester != current.fence.identity.userId -> "REQUESTER_REQUIRED"
            !current.platformAdmin && "inventory.approval.request" !in current.permissions -> "REQUEST_PERMISSION_REQUIRED"
            source.kind == "OPENING_BALANCE" && !current.platformAdmin && "inventory.provenance.manage" !in current.permissions -> "REQUEST_PERMISSION_REQUIRED"
            store.findSource(id, source.revision) != null -> "REQUEST_ALREADY_EXISTS"
            else -> {
                try { owner.validate(source, id); null }
                catch (failure: WarehouseContractException) {
                    if (failure.error.code != WarehouseErrorCode.SOURCE_NOT_VERIFIED) throw failure
                    "SOURCE_NOT_READY"
                }
            }
        }
        WarehouseApprovalSourceView(projection.document(source.content, source.locations), block == null, block)
    }

    override fun attachments(id: UUID, page: WarehousePageRequest): WarehousePage<WarehouseApprovalAttachment> = ownTransaction {
        if (page.page < 0 || page.size !in 1..100) masterFailure(WarehouseErrorCode.MALFORMED_REQUEST)
        evidence.list(locked(id).record, page)
    }
    override fun attachment(id: UUID, evidenceId: UUID): com.duluin.ftth.common.storage.StoredObject = ownTransaction {
        val (record, _, current) = locked(id)
        evidence.download(record, evidenceId, current.fence)
    }

    override fun migrationCases(id: UUID, page: WarehousePageRequest): WarehousePage<MigrationReviewCase> = ownTransaction {
        if (page.page < 0 || page.size !in 1..100) masterFailure(WarehouseErrorCode.MALFORMED_REQUEST)
        val record = locked(id).record
        if (record.snapshot.evaluation.operation != PolicyOperation.OPENING_BALANCE) masterFailure(WarehouseErrorCode.NOT_FOUND)
        val mapper = jacksonObjectMapper()
        val opening = mapper.treeToValue(mapper.readTree(record.snapshot.source).path("opening"), WarehouseMigrationOpening::class.java)
        val cases = opening.manifest.cases
        val offset = page.page.toLong() * page.size
        WarehousePage(if (offset >= cases.size) emptyList() else cases.drop(offset.toInt()).take(page.size), page.page, page.size, cases.size.toLong())
    }

    override fun details(id: UUID): WarehouseApprovalDetails = ownTransaction {
        val (record, source, current) = locked(id)
        val decisions = store.decisions(id)
        val evaluation = record.snapshot.evaluation
        val tier = evaluation.tiers.getOrNull(decisions.size)?.number
        val block = when {
            record.status != WarehouseApprovalStatus.PENDING -> "REQUEST_TERMINAL"
            cutovers.read().let { it.state != (if (source.kind == "OPENING_BALANCE") WarehouseCutoverState.VALIDATING else WarehouseCutoverState.ENFORCED) ||
                it.epoch != record.snapshot.cutoverEpoch } -> "CUTOVER_CHANGED"
            source.revision != evaluation.sourceRevision || WarehouseCanonicalPayload.parse(source.content).hash != record.snapshot.sourceHash -> "SOURCE_CHANGED"
            current.fence.identity.userId in evaluation.excludedUserIds -> "INDEPENDENT_APPROVER_REQUIRED"
            tier == null -> "NO_REMAINING_TIER"
            else -> try { eligibility.authorize(record, tier, current, decisions, policy.now()); null }
                catch (failure: WarehouseContractException) {
                    if (failure.error.code != WarehouseErrorCode.FORBIDDEN) throw failure
                    "NOT_CURRENT_APPROVER"
                }
        }
        val rework = record.status == WarehouseApprovalStatus.REWORK_REQUIRED && source.disposition == "REWORK_REQUIRED" &&
            record.snapshot.requesterId == current.fence.identity.userId && (current.platformAdmin || "inventory.approval.request" in current.permissions) &&
            source.kind !in setOf("ADJUSTMENT", "RETURN_TITLE", "TITLE_CORRECTION", "LOSS", "SCRAP", "DISPOSITION_REVERSAL", "ASSET_LOSS", "COUNT", "OPENING_BALANCE") && !store.isReplacement(evaluation.sourceDocumentId)
        val names = projection.people(evaluation.tiers.flatMap { it.approvers.map { it.userId } }.toSet())
        val policyView = WarehouseApprovalPolicyView(requireNotNull(evaluation.policy).id, evaluation.policy.revision,
            evaluation.tiers.map { item -> WarehouseApprovalTierView(item.number, item.approvers.map { projection.person(it.userId, names) }) })
        val view = ApprovalOutcomeCodec.view(record)
        val amount = if ((current.platformAdmin || "inventory.cost.view" in current.permissions) && evaluation.valueNumerator != null &&
            evaluation.valueDenominator != null && evaluation.currency != null) WarehouseApprovalAmount(evaluation.valueNumerator, evaluation.valueDenominator, evaluation.currency) else null
        WarehouseApprovalDetails(view, projection.document(record.snapshot.source, record.snapshot.locations), source.revision, source.state, record.requestedAt,
            policyView, WarehouseApprovalActions(block == null, block, rework, source.revision, tier), view.effectOperationId?.let { query.effect(id, it) }, amount)
    }

    override fun history(id: UUID, page: WarehousePageRequest): WarehousePage<WarehouseApprovalHistoryEntry> = ownTransaction {
        validateApprovalFilter(WarehouseApprovalFilter(page.page, page.size))
        locked(id)
        val result = query.history(id, page)
        val names = projection.people(result.items.flatMap { listOfNotNull(it.actorId, it.delegation?.approverId) }.toSet())
        WarehousePage(result.items.map { WarehouseApprovalHistoryEntry(it.id, it.tier, projection.person(it.actorId, names), it.decision.name,
            it.reason, it.decidedAt, it.revision, it.delegation?.approverId?.let { actor -> projection.person(actor, names) }, it.evidenceReference) }, page.page, page.size, result.totalElements)
    }

    private data class Locked(val record: WarehouseApprovalRecord, val source: ApprovalSourceState, val current: CurrentAuthority)
    private fun locked(id: UUID): Locked {
        val current = current()
        val preview = store.get(id)
        sourceLocks.forEach { it.lock(preview.snapshot.evaluation.sourceDocumentId, current) }
        masters.lockTopology()
        eligibility.view(preview, current)
        store.source(preview.snapshot.evaluation.sourceDocumentId)
        val record = store.get(id, true)
        if (record.status == WarehouseApprovalStatus.PENDING && policy.now() >= record.expiresAt) approvals.terminate(record, WarehouseApprovalStatus.EXPIRED)
        return Locked(store.get(id), store.source(preview.snapshot.evaluation.sourceDocumentId), current)
    }
    private fun current(): CurrentAuthority {
        cutovers.lockForCommand(cutovers.read().epoch, WarehouseOperationClass.CONTROL_PLANE)
        val current = authority.lockCurrent()
        access.permission(current, "inventory.approval.view")
        return current
    }
    private fun <T : Any> ownTransaction(action: () -> T): T {
        check(!TransactionSynchronizationManager.isActualTransactionActive()) { "Approval queries own their visibility transactions" }
        return requireNotNull(transaction.execute { action() })
    }
}

internal fun validateApprovalFilter(filter: WarehouseApprovalFilter) {
    if (filter.page < 0 || filter.size !in 1..100 || (filter.query != null && (filter.query.isBlank() || filter.query.length > 200)) ||
        (filter.from == null) != (filter.until == null) || (filter.from != null && filter.until != null &&
            (filter.from >= filter.until || Duration.between(filter.from, filter.until) > Duration.ofDays(366)))) masterFailure(WarehouseErrorCode.MALFORMED_REQUEST)
}

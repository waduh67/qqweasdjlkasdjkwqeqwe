package com.duluin.ftth.inventory.application.service

import com.duluin.ftth.iam.CurrentAuthorityApi
import com.duluin.ftth.inventory.*
import com.duluin.ftth.inventory.adapter.outbound.persistence.*
import com.duluin.ftth.inventory.application.port.inbound.masterFailure
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.math.BigInteger

@Service
class WarehousePolicyEvaluationService(private val cutovers: InventoryTenantCutoverApi, private val authority: CurrentAuthorityApi,
    private val access: WarehousePolicyAccess, private val store: WarehousePolicyPersistence, private val sources: WarehousePolicySource) : WarehousePolicyEvaluationApi {
    private val mapper = jacksonObjectMapper()
    @Transactional(rollbackFor = [Exception::class])
    override fun evaluate(source: WarehouseSourceInput): WarehousePolicyEvaluation = evaluateSource(source, null)
    @Transactional(rollbackFor = [Exception::class])
    override fun evaluateForAction(source: WarehouseSourceInput, action: PolicyOperation): WarehousePolicyEvaluation = evaluateSource(source, action)

    private fun evaluateSource(source: WarehouseSourceInput, action: PolicyOperation?): WarehousePolicyEvaluation {
        if (source.sourceRevision < 0) masterFailure(WarehouseErrorCode.MALFORMED_REQUEST)
        cutovers.lockForCommand(cutovers.read().epoch, WarehouseOperationClass.CONTROL_PLANE)
        val current = authority.lockCurrent()
        if (!current.platformAdmin && current.permissions.none { it in setOf("inventory.approval.view", "inventory.approval.request",
                "inventory.receipt.manage", "inventory.issue.manage", "inventory.count.manage") }) masterFailure(WarehouseErrorCode.FORBIDDEN)
        val document = sources.lock(source, action)
        if (!current.platformAdmin && "inventory.approval.view" !in current.permissions) access.permission(current, when (document.operation) {
            PolicyOperation.RECEIPT -> "inventory.receipt.manage"
            PolicyOperation.ISSUE -> "inventory.issue.manage"
            PolicyOperation.COUNT_VARIANCE -> "inventory.count.manage"
            else -> "inventory.approval.request"
        })
        val locations = document.lines.map { requireNotNull(it.locationId) }.distinct()
        locations.forEach { access.location(it, current) }
        val policy = store.current()
        val rule = policy?.rules?.singleOrNull { it.operation == document.operation }
        val excluded = (listOf(document.requesterId) + document.lines.mapNotNull { it.custodianId } + document.counters).toSet()
        var numerator: BigInteger? = null
        var denominator: BigInteger? = null
        val requirements = mutableListOf<PolicyTierRequirement>()
        if (rule == null && document.operation.exception) masterFailure(WarehouseErrorCode.INDEPENDENT_APPROVER_REQUIRED, "Configure an approval policy for ${document.operation}")
        if (rule != null) {
            if (locations.any { !store.covered(it, policy.warehouseIds) }) masterFailure(WarehouseErrorCode.NOT_FOUND)
            if (document.lines.any { it.numerator == null || it.denominator == null || it.currency == null })
                masterFailure(WarehouseErrorCode.COST_BASIS_REQUIRED, "Record the source receipt cost numerator, base quantity denominator and currency")
            if (document.lines.any { it.currency != policy.currency }) masterFailure(WarehouseErrorCode.CURRENCY_MISMATCH, "Policy currency ${policy.currency} must match each source cost; FX is not supported")
            var total = BigInteger.ZERO
            var basis = BigInteger.ONE
            document.lines.forEach { line ->
                val lineBasis = requireNotNull(line.denominator)
                total = total * lineBasis + requireNotNull(line.numerator) * line.quantity * basis
                basis *= lineBasis
                val divisor = total.gcd(basis)
                total /= divisor
                basis /= divisor
            }
            numerator = total
            denominator = basis
            val directory = access.directory(current)
            val now = store.now()
            val delegations = store.delegations().filter { it.revokedAt == null && it.validFrom <= now && it.validUntil > now && it.operation == document.operation }
            val excludedDelegates = delegations.filter { it.approverId in excluded }.map { it.delegateId }.toSet()
            rule.tiers.forEachIndexed { index, tier ->
                if ((document.operation.exception && index == 0) || total >= tier.minimumMinor.toBigInteger() * basis) {
                    val direct = directory.users.filter { it.id in tier.userIds || it.roleIds.any(tier.roleIds::contains) }
                    val candidates = direct.filter { it.id !in excluded && it.id !in excludedDelegates && access.eligible(it, locations) }
                        .map { PolicyEligibleApprover(it.id) }.toMutableList()
                    delegations.forEach { delegation ->
                        val delegator = direct.singleOrNull { it.id == delegation.approverId }
                        val delegate = directory.users.singleOrNull { it.id == delegation.delegateId }
                        val sourceMatches = if (delegation.sourceRoleId == null) delegation.approverId in tier.userIds
                            else delegation.sourceRoleId in tier.roleIds && delegation.sourceRoleId in delegator?.roleIds.orEmpty()
                        if (delegator != null && delegate != null && sourceMatches && delegation.approverId !in excluded &&
                            delegation.delegateId !in excluded && delegation.delegateId !in excludedDelegates &&
                            locations.all { store.covered(it, listOf(delegation.locationId)) } &&
                            access.eligible(delegator, locations) && access.eligible(delegate, locations)) {
                            candidates += PolicyEligibleApprover(delegate.id, delegator.id, delegation.id)
                        }
                    }
                    if (candidates.isEmpty()) masterFailure(WarehouseErrorCode.INDEPENDENT_APPROVER_REQUIRED,
                        "Tier ${index + 1} requires an active independent approver with warehouse and area access")
                    requirements += PolicyTierRequirement(index + 1, tier.minimumMinor, candidates.distinct().sortedBy { it.userId.toString() })
                }
            }
        }
        val result = WarehousePolicyEvaluation(if (requirements.isEmpty()) "IN_POLICY" else "APPROVAL_REQUIRED", source.sourceDocumentId,
            source.sourceRevision, document.operation, policy, numerator?.toString(), denominator?.toString(), if (numerator == null) null else policy?.currency,
            excluded, requirements, "", current.fence.epoch)
        return result.copy(snapshotHash = WarehouseCanonicalPayload.parse(mapper.writeValueAsString(result)).hash)
    }
}

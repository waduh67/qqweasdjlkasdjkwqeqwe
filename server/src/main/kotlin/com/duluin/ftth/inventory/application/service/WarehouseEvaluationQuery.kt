package com.duluin.ftth.inventory.application.service

import com.duluin.ftth.iam.CurrentAuthorityApi
import com.duluin.ftth.inventory.*
import com.duluin.ftth.inventory.application.port.inbound.*
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

@Service
class WarehouseEvaluationQuery(private val evaluation: WarehousePolicyEvaluationApi, private val authority: CurrentAuthorityApi,
    private val access: WarehousePolicyAccess, private val transactions: PlatformTransactionManager) {
    fun evaluate(source: WarehouseSourceInput): WarehouseEvaluationResult {
        try {
            return WarehouseEvaluationResult(200, requireNotNull(TransactionTemplate(transactions).execute { project(source) }))
        } catch (failure: WarehouseContractException) {
            val resolution = when (failure.error.code) {
                WarehouseErrorCode.COST_BASIS_REQUIRED -> "PROVIDE_COST_BASIS" to "Record the source receipt cost basis before requesting approval."
                WarehouseErrorCode.CURRENCY_MISMATCH -> "ALIGN_CURRENCY" to "Ask the policy administrator to align source and policy currencies; currency conversion is not supported."
                WarehouseErrorCode.INDEPENDENT_APPROVER_REQUIRED -> "CONFIGURE_APPROVERS" to "Ask the policy administrator to configure a currently eligible independent approver."
                else -> throw failure
            }
            return WarehouseEvaluationResult(failure.error.code.httpStatus, WarehouseOperationEvaluation(failure.error.code.name,
                source.sourceDocumentId, source.sourceRevision, resolution.first, resolution.second))
        }
    }
    private fun project(source: WarehouseSourceInput): WarehouseEvaluationView {
        val full = evaluation.evaluate(source)
        val current = authority.lockCurrent()
        val resolution = when (full.code) {
            "IN_POLICY" -> "CONTINUE_OPERATION" to "Continue through the normal warehouse operation; no approval is required."
            "APPROVAL_REQUIRED" -> "REQUEST_APPROVAL" to "Request independent document approval before posting this operation."
            else -> error("Unsupported policy evaluation outcome")
        }
        val status = WarehouseOperationEvaluation(full.code, full.sourceDocumentId, full.sourceRevision, resolution.first, resolution.second)
        val policy = full.policy
        if ("inventory.approval.view" !in current.permissions || policy == null) return status
        val visibleWarehouses = policy.warehouseIds.filter { id ->
            try { access.location(id, current); true } catch (failure: WarehouseContractException) {
                if (failure.error.code != WarehouseErrorCode.NOT_FOUND) throw failure
                false
            }
        }
        if (visibleWarehouses.isEmpty()) return status
        val policyView = WarehouseEvaluationPolicy(policy.id, policy.revision, policy.expiryHours, visibleWarehouses, full.operation)
        val tiers = full.tiers.map { tier -> WarehouseEvaluationTier(tier.number, tier.approvers.map { candidate ->
            if (candidate.delegatedFrom == null) WarehouseDirectApprover(candidate.userId)
            else WarehouseDelegatedApprover(candidate.userId, candidate.delegatedFrom, requireNotNull(candidate.delegationId))
        }) }
        val numerator = full.valueNumerator
        val denominator = full.valueDenominator
        val currency = full.currency
        val view = if ("inventory.cost.view" in current.permissions && numerator != null && denominator != null && currency != null)
            WarehouseCostEvaluation(status.code, status.sourceDocumentId, status.sourceRevision, status.requiredAction, status.message,
                policyView, tiers, numerator, denominator, currency)
        else WarehouseApprovalEvaluation(status.code, status.sourceDocumentId, status.sourceRevision, status.requiredAction, status.message, policyView, tiers)
        return view
    }
}

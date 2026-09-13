package com.duluin.ftth.inventory.application.service

import com.duluin.ftth.iam.CurrentAuthorityApi
import com.duluin.ftth.inventory.*
import com.duluin.ftth.inventory.adapter.outbound.persistence.MaterialLifecycleStore
import com.duluin.ftth.inventory.adapter.outbound.persistence.MaterialPlanningStore
import com.duluin.ftth.inventory.adapter.outbound.persistence.MaterialReworkStore
import com.duluin.ftth.inventory.adapter.outbound.persistence.MaterialUsageStore
import com.duluin.ftth.inventory.adapter.outbound.persistence.MaterialPhysicalTotalsStore
import com.duluin.ftth.inventory.application.port.inbound.masterFailure
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.util.UUID

@Service
@Transactional(propagation = Propagation.MANDATORY, rollbackFor = [Exception::class])
class InventoryMaterialReworkService(private val authority: CurrentAuthorityApi, private val plans: MaterialPlanningStore,
    private val reworks: MaterialReworkStore, private val usage: MaterialUsageStore, private val totals: MaterialPhysicalTotalsStore,
    private val lifecycle: MaterialLifecycleStore, private val validation: MaterialPlanValidation) : InventoryMaterialReworkApi {
    private val mapper = jacksonObjectMapper()

    override fun basis(context: MaterialReworkContext): MaterialReworkBasis {
        val material = context.material
        material.cutover.assertHeld()
        material.authority.assertHeld()
        receiptPermission(authority.lockCurrent(), "inventory.request.view")
        lifecycle.lock(material.workOrderId)
        val plan = plans.current(material.workOrderId)?.plan ?: masterFailure(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
        val prior = reworks.latestUsage(material.workOrderId)
        usage.get(prior.first)
        return MaterialReworkBasis(plan.planRevision, material.workOrderRevision, plan.id, prior.first, prior.second,
            context.previousEvidenceRevision, context.evidenceRevision)
    }

    override fun append(context: MaterialReworkContext, request: MaterialReworkRequest, metadata: WarehouseMutationMetadata): WarehouseOperationReceipt {
        val material = context.material
        material.cutover.assertHeld()
        material.authority.assertHeld()
        val current = authority.lockCurrent()
        if (current.fence.identity != material.authority.identity || current.fence.epoch != material.authority.epoch) masterFailure(WarehouseErrorCode.STALE_AUTHORITY)
        receiptPermission(current, "inventory.request.manage")
        receiptPermission(current, "inventory.request.view")
        receiptKey(metadata.idempotencyKey)
        if (request.reason.isBlank() || request.reason.length > 1000 || request.expectedRevision !in 1 until Long.MAX_VALUE ||
            request.expectedUsageRevision < 1 || request.deltas.isEmpty() || request.deltas.any { it.substitution != null })
            masterFailure(WarehouseErrorCode.MALFORMED_REQUEST)
        if (request.workOrderRevision != material.workOrderRevision || request.previousEvidenceRevision != context.previousEvidenceRevision ||
            request.evidenceRevision != context.evidenceRevision) masterFailure(WarehouseErrorCode.STALE_REVISION)
        lifecycle.lock(material.workOrderId)
        val canonical = WarehouseCanonicalPayload.parse(mapper.writeValueAsString(request))
        reworks.replay(material, metadata.idempotencyKey, canonical.hash)?.let { return it }
        if (lifecycle.summary(material.workOrderId).materialState == ResidualSettlementState.CLOSED) masterFailure(WarehouseErrorCode.STALE_REVISION)
        val previous = plans.current(material.workOrderId)?.takeIf { it.state == "SUBMITTED" }?.plan ?: masterFailure(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
        if (previous.id != request.previousPlanId || previous.planRevision != request.expectedRevision ||
            previous.customerId != material.customerId || previous.workType != material.workType || previous.action != material.action ||
            previous.materialMode != MaterialMode.MATERIAL_REQUIRED || totals.useRevision(material.workOrderId) != request.expectedUsageRevision)
            masterFailure(WarehouseErrorCode.STALE_REVISION)
        val priorUsage = usage.get(request.previousUsageId)
        if (priorUsage.workOrderId != material.workOrderId || priorUsage.useRevision != request.expectedUsageRevision)
            masterFailure(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
        val inherited = (reworks.get(previous.id)?.inheritedLines.orEmpty() + previous.lines).distinctBy { it.id }
        val lines = validation.lines(request.deltas, current, previous)
        val now = plans.now()
        val plan = MaterialPlanSnapshot(UUID.randomUUID(), material.workOrderId, material.code, material.workType, material.action,
            material.customerId, material.workOrderRevision, Math.addExact(previous.planRevision, 1), MaterialMode.MATERIAL_REQUIRED,
            request.reason, null, current.fence.identity.userId, lines, now)
        plans.insert(plan)
        val snapshot = MaterialReworkSnapshot(plan.id, previous, plan, priorUsage.usageId, priorUsage.useRevision,
            context.previousEvidenceRevision, context.evidenceRevision, context.evidence, inherited, request.reason, plan.actorId, now)
        val receipt = reworks.record(material, snapshot, metadata.idempotencyKey to canonical.hash)
        plans.submit(plan, material)
        return receipt
    }
}

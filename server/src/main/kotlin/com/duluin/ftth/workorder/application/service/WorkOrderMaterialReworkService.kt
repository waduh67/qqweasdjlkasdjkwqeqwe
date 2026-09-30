package com.duluin.ftth.workorder.application.service

import com.duluin.ftth.common.security.AuthorityFence
import com.duluin.ftth.iam.CurrentAuthorityApi
import com.duluin.ftth.inventory.WarehouseContractException
import com.duluin.ftth.inventory.WarehouseError
import com.duluin.ftth.inventory.WarehouseErrorCode
import com.duluin.ftth.workorder.*
import com.duluin.ftth.workorder.application.port.outbound.WorkOrderEvidenceRepository
import com.duluin.ftth.workorder.application.port.outbound.WorkOrderRepository
import com.duluin.ftth.workorder.application.port.outbound.WorkOrderSignatureRepository
import com.duluin.ftth.workorder.domain.model.ProofArtifactCompatibility
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.security.MessageDigest
import java.util.UUID

@Service
class WorkOrderMaterialReworkService(private val contexts: WorkOrderMaterialContextApi, private val workOrders: WorkOrderRepository,
    private val evidence: WorkOrderEvidenceRepository, private val signatures: WorkOrderSignatureRepository,
    private val authority: CurrentAuthorityApi) : WorkOrderMaterialReworkApi {
    @Transactional(propagation = Propagation.MANDATORY)
    override fun lock(workOrderId: UUID, authority: AuthorityFence): WorkOrderReworkContext {
        authority.assertHeld()
        val current = this.authority.lockCurrent()
        val context = contexts.lockForCustody(workOrderId, authority)
        if (!current.platformAdmin && current.permissions.none { it in setOf("workorder.order.update", "workorder.order.approve") } &&
            ("workorder.order.field" !in current.permissions || authority.identity.userId !in context.material.activeAssigneeIds))
            fail(WarehouseErrorCode.FORBIDDEN)
        if (context.technicalState != "IN_PROGRESS" || context.qaState != "REJECTED") fail(WarehouseErrorCode.STALE_REVISION)
        val workOrder = workOrders.findById(workOrderId) ?: fail(WarehouseErrorCode.NOT_FOUND)
        val previous = workOrder.proofOfWorkHash ?: fail(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
        val references = buildList {
            evidence.listByWorkOrder(workOrderId).forEach { item ->
                ProofArtifactCompatibility.fromEvidence(item.kind)?.let { add(WorkOrderReworkEvidence(item.id, it.name, WorkOrderEvidenceSource.PHOTO)) }
            }
            signatures.findByWorkOrder(workOrderId)?.let {
                add(WorkOrderReworkEvidence(it.id, ProofArtifactCompatibility.customerSignature.name, WorkOrderEvidenceSource.SIGNATURE))
            }
        }.sortedBy { it.revisionId.toString() }
        if (references.isEmpty()) fail(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
        val revision = MessageDigest.getInstance("SHA-256").digest(references.joinToString("|") { it.revisionId.toString() }.toByteArray())
            .joinToString("") { "%02x".format(it) }
        return WorkOrderReworkContext(context.material, previous, revision, references)
    }

    private fun fail(code: WarehouseErrorCode): Nothing = throw WarehouseContractException(WarehouseError(code, code.name))
}

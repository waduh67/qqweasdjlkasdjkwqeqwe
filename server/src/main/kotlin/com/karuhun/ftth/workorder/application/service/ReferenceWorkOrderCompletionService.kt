package com.karuhun.ftth.workorder.application.service

import com.karuhun.ftth.inventory.InventoryReferenceConsumptionApi
import com.karuhun.ftth.inventory.ReferenceConsumptionCommand
import com.karuhun.ftth.inventory.ReferenceConsumptionSelection
import com.karuhun.ftth.inventory.ReferenceConsumptionProof
import com.karuhun.ftth.inventory.WarehouseErrorCode
import com.karuhun.ftth.inventory.WarehouseOperationReceipt
import com.karuhun.ftth.common.warehouse.warehouseFailure
import com.karuhun.ftth.common.infrastructure.web.WarehouseCanonicalPayload
import com.karuhun.ftth.common.infrastructure.time.referenceTimestamp
import com.karuhun.ftth.workorder.adapter.outbound.persistence.ReferenceWorkOrderCompletionStore
import com.karuhun.ftth.workorder.adapter.outbound.persistence.ReferenceWorkOrderStore
import com.karuhun.ftth.workorder.application.port.inbound.*
import com.karuhun.ftth.workorder.application.port.outbound.WorkOrderRepository
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.util.UUID

@Service
@Transactional
class ReferenceWorkOrderCompletionService(private val orders: ReferenceWorkOrderService, private val store: ReferenceWorkOrderStore,
    private val proofs: ReferenceWorkOrderEvidenceService, private val completions: ReferenceWorkOrderCompletionStore,
    private val workOrders: WorkOrderRepository, private val consumption: InventoryReferenceConsumptionApi,
    private val entityManager: EntityManager, private val events: org.springframework.context.ApplicationEventPublisher) {
    private val mapper = jacksonObjectMapper()

    fun complete(id: UUID, input: ReferenceWorkOrderCompletionInput, key: String): WarehouseOperationReceipt {
        val access = orders.access(key)
        val current = orders.authorized(store.get(id, true), access)
        orders.field(current, access)
        val canonical = orders.canonical(id, input)
        orders.replay("COMPLETE", key, canonical, access)?.let { return it }
        val prior = orders.editable(id, input.expectedRevision, access)
        orders.text(input.notes, 1000)
        if (input.materials.size > 100 || input.materials.distinctBy { it.stockIdentityId }.size != input.materials.size ||
            prior.type.materialRequired && input.materials.isEmpty()) warehouseFailure(WarehouseErrorCode.MALFORMED_REQUEST, "Pilih material yang dipakai")
        val photos = proofs.requireCurrent(id, prior.assignmentGeneration, prior.type.photoSlots).sortedBy { it.caption }
            .map { ReferenceCompletionPhoto(it.id, requireNotNull(it.caption), requireNotNull(it.sha256), it.sizeBytes, it.contentType) }
        val actor = access.current.fence.identity.userId
        val now = referenceTimestamp()
        val sealedProof = consumption.consume(ReferenceConsumptionCommand(id, prior.customerId, prior.technicianName, prior.revision + 1,
            now, input.notes, input.materials.map { ReferenceConsumptionSelection(it.stockIdentityId, it.quantityBase) },
            canonical, access.current, access.cutover.snapshot.epoch)) { result ->
            val materials = result.materials.map { material ->
                ReferenceCompletionMaterial(material.lineId, material.stockIdentityId, material.consumedIdentityId, material.skuId,
                    material.skuName, material.quantityBase, material.baseUnit, material.tracking, material.serial, material.mac)
            }
            val completion = ReferenceWorkOrderCompletionView(id, prior.revision + 1, prior.assignmentGeneration, actor, input.notes,
                now, photos, materials, result.documentId)
            val proof = WarehouseCanonicalPayload.parse(mapper.writeValueAsString(completion))
            completions.save(completion, proof)
            ReferenceConsumptionProof(completion, proof)
        }
        val completion = sealedProof.value
        val proof = sealedProof.canonical
        val order = workOrders.findById(id) ?: warehouseFailure(WarehouseErrorCode.NOT_FOUND)
        order.completeReference(input.notes, proof.hash, now, actor)
        workOrders.save(order)
        entityManager.flush()
        val view = prior.copy(revision = completion.revision, state = ReferenceWorkOrderState.COMPLETED, lastActivityAt = now, blockedReason = null)
        store.save(view, false)
        val receipt = orders.record("COMPLETE", key, canonical, access, id, "WO", view.revision, view, input.notes, "workorder.order.field")
        events.publishEvent(com.karuhun.ftth.workorder.ReferenceWorkOrderCompleted(access.current.fence.identity.tenantId, id))
        return receipt
    }
}

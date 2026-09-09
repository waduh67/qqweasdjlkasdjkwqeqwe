package com.duluin.ftth.inventory.application.service

import com.duluin.ftth.inventory.*
import com.duluin.ftth.inventory.adapter.outbound.persistence.WarehouseReservationStore
import com.duluin.ftth.inventory.application.port.inbound.masterFailure
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class MaterialReservationService(private val reservations: InventoryReservationApi, private val store: WarehouseReservationStore) : InventoryMaterialReservationApi {
    @Transactional(timeout = 30, rollbackFor = [Exception::class])
    override fun reserve(workOrderId: UUID, request: MaterialDocumentRequest, metadata: WarehouseMutationMetadata) =
        execute(workOrderId, request, metadata, ReservationAction.RESERVE)

    @Transactional(timeout = 30, rollbackFor = [Exception::class])
    override fun release(workOrderId: UUID, request: MaterialDocumentRequest, metadata: WarehouseMutationMetadata) =
        execute(workOrderId, request, metadata, ReservationAction.RELEASE)

    private fun execute(workOrderId: UUID, request: MaterialDocumentRequest, metadata: WarehouseMutationMetadata, action: ReservationAction): WarehouseOperationReceipt {
        val command = request.reservation ?: masterFailure(WarehouseErrorCode.MALFORMED_REQUEST)
        if (command.expectedRevision != request.expectedRevision) masterFailure(WarehouseErrorCode.STALE_REVISION)
        if (store.demand(request.documentId).workOrder != workOrderId) masterFailure(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
        return reservations.execute(request.documentId, action, command, metadata)
    }
}

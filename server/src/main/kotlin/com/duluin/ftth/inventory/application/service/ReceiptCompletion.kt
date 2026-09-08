package com.duluin.ftth.inventory.application.service

import com.duluin.ftth.inventory.WarehouseReceiptState
import com.duluin.ftth.inventory.adapter.outbound.persistence.WarehouseReceiptPersistence
import com.duluin.ftth.inventory.application.port.inbound.ReceiptRecord
import com.duluin.ftth.inventory.domain.model.InventoryStatus
import com.duluin.ftth.inventory.domain.model.LegDirection
import org.springframework.stereotype.Component

@Component
class ReceiptCompletion(private val store: WarehouseReceiptPersistence) {
    fun state(record: ReceiptRecord, pending: ReceiptDispositionPlan? = null): WarehouseReceiptState {
        val inspected = store.inspections(record.id).groupBy { it.lineId }
        var anyPutaway = false
        val complete = record.intake.lines.map { line ->
            val rejected = (inspected[line.id].orEmpty().map { it.rejectedBase.toLong() } +
                pending?.decisions.orEmpty().filter { it.input.lineId == line.id }.map { it.input.rejectedBase.toLong() })
                .fold(0L, Math::addExact)
            val placed = pending?.legs.orEmpty().filter {
                it.documentLineId == line.id && it.direction == LegDirection.IN && it.status == InventoryStatus.AVAILABLE
            }.map { it.quantity.quantityBase }.fold(store.putawayBase(line.id).toLong(), Math::addExact)
            anyPutaway = anyPutaway || placed > 0
            Math.addExact(rejected, placed) == line.quantityBase.toLong()
        }.all { it }
        return if (!complete) WarehouseReceiptState.RECEIVED_IN_INSPECTION
            else if (anyPutaway) WarehouseReceiptState.PUTAWAY else WarehouseReceiptState.CLOSED
    }
}

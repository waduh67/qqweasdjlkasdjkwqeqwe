package com.duluin.ftth.inventory

import java.util.UUID

data class WarehouseDeliveryMessage(val event: WarehouseDocumentEvent, val postingId: UUID)

interface WarehouseOutboxDeliveryPort {
    fun deliver(message: WarehouseDeliveryMessage)
}

package com.duluin.ftth.inventory

import java.util.UUID

interface WarehouseInboxApi {
    fun consume(eventId: UUID, consumer: String, localEffect: (WarehouseDocumentEvent) -> Unit): Boolean
}

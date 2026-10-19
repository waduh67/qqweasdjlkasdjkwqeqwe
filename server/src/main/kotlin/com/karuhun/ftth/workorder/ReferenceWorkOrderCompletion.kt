package com.karuhun.ftth.workorder

import java.time.Instant
import java.util.UUID

data class ReferenceCompletionPhoto(val id: UUID, val slot: String, val sha256: String, val sizeBytes: Long, val contentType: String)
data class ReferenceCompletionMaterial(val lineId: UUID, val stockIdentityId: UUID, val consumedIdentityId: UUID,
    val skuId: UUID, val skuName: String, val quantityBase: String, val baseUnit: String, val tracking: String,
    val serial: String?, val mac: String?)
data class ReferenceWorkOrderCompletionView(val workOrderId: UUID, val revision: Long, val assignmentGeneration: Long,
    val technicianId: UUID, val notes: String, val completedAt: Instant, val photos: List<ReferenceCompletionPhoto>,
    val materials: List<ReferenceCompletionMaterial>, val documentId: UUID?)

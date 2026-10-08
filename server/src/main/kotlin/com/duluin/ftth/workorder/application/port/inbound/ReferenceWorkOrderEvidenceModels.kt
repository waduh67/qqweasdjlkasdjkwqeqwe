package com.duluin.ftth.workorder.application.port.inbound

import java.time.Instant
import java.util.UUID

data class ReferenceWorkOrderPhotoView(val id: UUID, val slot: String, val assignmentGeneration: Long,
    val uploadedBy: UUID, val uploadedByName: String, val contentType: String, val sizeBytes: Long,
    val sha256: String, val receivedAt: Instant, val current: Boolean)
data class ReferenceWorkOrderPhotoInput(val expectedRevision: Long, val slot: String, val contentType: String,
    val sizeBytes: Long, val sha256: String)

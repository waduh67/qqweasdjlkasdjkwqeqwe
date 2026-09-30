package com.duluin.ftth.inventory

import java.time.Instant
import java.util.UUID

data class WarehouseMigrationEvidence(val id: UUID, val batchId: UUID, val caseId: UUID, val sourceHash: String,
    val label: String, val contentType: String, val sizeBytes: Long, val sha256: String, val uploadedBy: UUID, val createdAt: Instant)

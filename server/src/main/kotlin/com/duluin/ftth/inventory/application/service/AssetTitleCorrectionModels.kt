package com.duluin.ftth.inventory.application.service

import com.duluin.ftth.inventory.*
import java.time.Instant
import java.util.UUID

data class AssetTitleCorrectionSnapshot(val id: UUID, val code: String, val handover: AssetHandoverRecord,
    val source: CurrentAssetOwnership, val position: AssetHandoverPosition, val targetOwner: AssetLegalOwner,
    val reason: String, val evidence: AssetHandoverSignature, val actorId: UUID, val workOrderRevision: Long,
    val authorityEpoch: Long, val cutoverEpoch: Long, val requestedAt: Instant) {
    fun reference() = AssetTitleCorrectionRef(id, source.assignmentId, source.titleRevision, targetOwner)
}

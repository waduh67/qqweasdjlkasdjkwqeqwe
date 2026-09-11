package com.duluin.ftth.monitoring.application.port.inbound

import java.time.Instant
import java.util.UUID

interface ManualOltPollUseCase {
    fun pollOlt(oltId: UUID): ManualOltPollResult
}

class OltPollPersistenceException : RuntimeException(PUBLIC_MESSAGE) {
    companion object {
        const val PUBLIC_MESSAGE = "Polling OLT gagal diproses"
    }
}

data class ManualOltPollResult(
    val oltId: UUID,
    val oltCode: String,
    val reachable: Boolean,
    val readingCount: Int,
    val failureReason: String?,
    val checkedAt: Instant,
)

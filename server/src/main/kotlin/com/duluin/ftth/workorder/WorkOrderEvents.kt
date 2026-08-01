package com.duluin.ftth.workorder

import java.time.Instant
import java.util.UUID

/**
 * Peristiwa saat sebuah work order ditugaskan ke teknisi — diterbitkan module
 * workorder setelah assignment ter-commit.
 *
 * Diletakkan di base package (permukaan publik workorder) sebagai titik kait untuk
 * push/notifikasi aplikasi teknisi mobile: konsumen (mis. module notification)
 * mendengarkan pada fase AFTER_COMMIT tanpa bergantung pada internal workorder.
 * Belum ada konsumen fungsional saat ini selain listener log tipis.
 */
data class WorkOrderAssigned(
    val tenantId: UUID,
    val workOrderId: UUID,
    val code: String,
    val title: String,
    /** Roster teknisi yang ditugaskan (tim datar); konsumen notifikasi mem-fan-out ke tiap id. */
    val technicianIds: List<UUID>,
    val scheduledAt: Instant?,
)

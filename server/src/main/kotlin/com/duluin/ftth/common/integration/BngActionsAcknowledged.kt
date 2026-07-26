package com.duluin.ftth.common.integration

import java.util.UUID

/**
 * Dipublikasikan monitoring saat collector meng-ACK perintah BRAS yang sebelumnya
 * dititipkan lewat [CollectorConfigContributor.pendingBngActionsFor] (jalur turun,
 * Phase 7c). Module `bng` mendengarkannya (AFTER_COMMIT) untuk menuntaskan
 * `bng_action` yang terkait — menandainya SELESAI atau GAGAL.
 *
 * Diletakkan di shared kernel `common` dengan alasan sama seperti [BngSessionsReported]:
 * monitoring memiliki kanal collector namun tak boleh bergantung pada `bng` (akan jadi
 * siklus module). Payload dinetralkan dari DTO wire (`contract.BngActionResult`) menjadi
 * tipe common; monitoring yang memetakannya.
 */
data class BngActionsAcknowledged(
    val tenantId: UUID,
    val collectorId: UUID,
    val results: List<AcknowledgedBngAction>,
)

/**
 * Hasil eksekusi satu perintah BRAS oleh collector, tipe netral shared-kernel.
 * [actionId] mengacu ke `bng_action` yang dititipkan; [detail] mengangkut pesan
 * error singkat saat gagal (null saat sukses).
 */
data class AcknowledgedBngAction(
    val actionId: UUID,
    val success: Boolean,
    val detail: String?,
)

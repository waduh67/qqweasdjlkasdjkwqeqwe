package com.duluin.ftth.notification.application.port.outbound

import com.duluin.ftth.common.domain.Page
import com.duluin.ftth.common.domain.PageRequest
import com.duluin.ftth.notification.domain.model.Broadcast
import com.duluin.ftth.notification.domain.model.NotificationChannel
import java.time.Instant
import java.util.UUID

interface BroadcastRepository {

    /** Menyimpan broadcast beserta seluruh penerimanya. */
    fun save(broadcast: Broadcast): Broadcast

    /** Broadcast lengkap dengan penerimanya, untuk halaman detail. */
    fun findById(id: UUID): Broadcast?

    /**
     * Riwayat broadcast terbaru tanpa memuat baris penerima — jumlahnya dibaca dari
     * kolom hitung ter-denormalisasi di baris broadcast, jadi daftar tetap ringan.
     */
    fun recent(request: PageRequest): Page<BroadcastDigest>
}

/** Ringkasan satu broadcast dari kolom hitungnya sendiri, tanpa memuat penerima. */
data class BroadcastDigest(
    val id: UUID,
    val incidentId: UUID?,
    val channel: NotificationChannel,
    val message: String,
    val createdBy: UUID,
    val createdAt: Instant,
    val recipientCount: Int,
    val sentCount: Int,
    val skippedCount: Int,
    val failedCount: Int,
)

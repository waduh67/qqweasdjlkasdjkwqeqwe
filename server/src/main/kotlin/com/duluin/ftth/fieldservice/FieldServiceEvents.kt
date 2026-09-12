package com.duluin.ftth.fieldservice

import java.time.Instant
import java.util.UUID

data class VisitCheckedIn(val tenantId: UUID, val visitId: UUID, val orderId: UUID, val workOrderId: UUID, val technicianId: UUID, val receivedAt: Instant)
data class VisitOnSite(val tenantId: UUID, val visitId: UUID, val orderId: UUID, val workOrderId: UUID, val technicianId: UUID, val receivedAt: Instant)
data class VisitCheckedOut(val tenantId: UUID, val visitId: UUID, val orderId: UUID, val workOrderId: UUID, val technicianId: UUID, val receivedAt: Instant)
data class VisitSubmitted(val tenantId: UUID, val visitId: UUID, val orderId: UUID, val workOrderId: UUID, val technicianId: UUID, val receivedAt: Instant)
data class VisitConflict(val tenantId: UUID, val visitId: UUID, val orderId: UUID, val workOrderId: UUID, val revision: Long, val receivedAt: Instant)

/**
 * SEBAB kunjungan dibatalkan — enum sempit, bukan teks bebas.
 *
 * Yang memutuskan apakah bola sekarang ada di tangan PELANGGAN atau di tangan KAMI adalah
 * sebabnya, dan keputusan itu diambil mesin (lihat pemicu `WAITING_CUSTOMER` di module order).
 * Kalau sebabnya teks bebas, pemicu itu harus menebak-nebak kalimat teknisi — "gak ada orang",
 * "kosong", "tutup" — dan setiap tebakan yang meleset memberi tahu pelanggan bahwa dia yang
 * harus bertindak padahal bukan.
 *
 * Tinggal di paket dasar (bukan di `domain.model`) SENGAJA: module order membacanya lewat
 * [VisitCancelled], dan ModularityTests menolak impor ke paket internal module lain.
 */
enum class VisitCancellationCause {
    CUSTOMER_NOT_PRESENT,
    PREMISE_LOCKED,
    CUSTOMER_REFUSED_INSTALL_POINT,
    CUSTOMER_RESCHEDULED,

    /**
     * Alamat tak ditemukan SENGAJA tidak digolongkan sebagai sebab pelanggan meski datanya dari
     * dia: teknisi baru saja gagal menemukannya, dan belum ada yang memverifikasi apakah
     * alamatnya yang keliru atau pencariannya.
     */
    ADDRESS_NOT_FOUND,
    TECHNICAL_BLOCKER,
    INTERNAL_RESCHEDULE,
    ;

    // CATATAN: pemetaan "sebab mana yang berarti bola ada di tangan pelanggan" TIDAK ditaruh di
    // sini sebagai flag boolean. Ia hidup di satu tempat saja — pemetaan sebab → narasi di
    // module order — supaya tidak pernah ada dua sumber kebenaran yang menyimpang diam-diam.
}

/**
 * Kunjungan dibatalkan di lapangan. [reason] adalah catatan INTERNAL teknisi dan TIDAK BOLEH
 * dikirim apa adanya ke pelanggan — kalimat untuk pelanggan disusun penerimanya dari [cause].
 */
data class VisitCancelled(
    val tenantId: UUID,
    val visitId: UUID,
    val orderId: UUID,
    val workOrderId: UUID,
    val technicianId: UUID,
    val cause: VisitCancellationCause,
    val reason: String,
    val occurredAt: Instant,
)

package com.duluin.ftth.common.integration

import java.time.Instant
import java.util.UUID

/**
 * Dipublikasikan monitoring saat collector mengirim batch sesi PPPoE dari sebuah
 * BRAS. Module `bng` mendengarkannya (AFTER_COMMIT) untuk memutakhirkan sesi
 * terkini + mencatat akunting deret-waktu.
 *
 * Diletakkan di shared kernel `common` supaya monitoring — yang memiliki kanal
 * collector — tidak perlu bergantung pada `bng`; kalau monitoring memanggil bng
 * langsung, sepasang ketergantungan itu menjadi siklus module. Payload sesi
 * dinetralkan dari DTO wire (`contract.RadiusSessionReading`) menjadi tipe common;
 * monitoring yang memetakannya. Sama polanya dengan [OpticalDegradationDetected].
 */
data class BngSessionsReported(
    val tenantId: UUID,
    val collectorId: UUID,
    val nasId: UUID,
    /** Identitas batch collector; dipakai bng untuk membuang kiriman ganda. */
    val batchId: String,
    val collectedAt: Instant,
    val sessions: List<ReportedRadiusSession>,
)

/**
 * Satu sesi PPPoE yang dilaporkan, tipe netral shared-kernel. Octet KUMULATIF
 * sejak sesi mulai; laju Mbps dihitung consumer dari selisih antar-laporan.
 */
data class ReportedRadiusSession(
    val username: String,
    val online: Boolean,
    val framedIp: String?,
    val nasIp: String?,
    val sessionId: String?,
    val callingStationId: String?,
    val uptimeSeconds: Long?,
    /** Arah unggah pelanggan (masuk ke BRAS). */
    val inOctets: Long?,
    /** Arah unduh pelanggan (keluar dari BRAS). */
    val outOctets: Long?,
)

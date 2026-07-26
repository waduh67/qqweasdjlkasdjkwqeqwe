package com.duluin.ftth.cpe.domain.model

import com.duluin.ftth.common.domain.UuidV7
import java.time.Instant
import java.util.UUID

/** Jenis perintah yang bisa dikirim ke CPE lewat ACS. */
enum class CpeActionType { REBOOT, SET_WIFI, PING_TEST, SPEED_TEST, FIRMWARE_UPGRADE, FACTORY_RESET, REFRESH_ACS }

/** Hasil eksekusi perintah — ACS bisa menolak atau timeout, jadi outcome dicatat. */
enum class CpeActionStatus { SUCCESS, FAILED }

/**
 * Jejak audit satu perintah yang dikirim ke CPE pelanggan.
 *
 * Perintah ke perangkat rumah pelanggan harus bisa dipertanggungjawabkan: siapa
 * mereboot router siapa, kapan, dan berhasil atau tidak. Ditulis untuk keberhasilan
 * maupun kegagalan — justru kegagalan yang paling perlu jejaknya saat pelanggan
 * komplain "internet saya mati setelah ada yang otak-atik".
 */
class CpeActionLog private constructor(
    val id: UUID,
    val deviceId: UUID,
    val action: CpeActionType,
    val status: CpeActionStatus,
    /** Ringkasan perintah atau pesan kegagalan, mis. "SSID→RumahAndi" atau error ACS. */
    val detail: String?,
    val requestedBy: UUID,
    /**
     * Email pelaku disimpan bersama barisnya (didenormalisasi, seperti `audit_log`)
     * agar riwayat aksi bisa menampilkan "siapa" tanpa memanggil module iam —
     * dan tetap terbaca meski pengguna itu kelak dihapus.
     */
    val requestedByEmail: String?,
    val requestedAt: Instant,
) {
    companion object {
        fun succeeded(
            deviceId: UUID,
            action: CpeActionType,
            detail: String?,
            requestedBy: UUID,
            requestedByEmail: String?,
            at: Instant = Instant.now(),
        ): CpeActionLog =
            CpeActionLog(UuidV7.generate(), deviceId, action, CpeActionStatus.SUCCESS, detail, requestedBy, requestedByEmail, at)

        fun failed(
            deviceId: UUID,
            action: CpeActionType,
            detail: String?,
            requestedBy: UUID,
            requestedByEmail: String?,
            at: Instant = Instant.now(),
        ): CpeActionLog =
            CpeActionLog(UuidV7.generate(), deviceId, action, CpeActionStatus.FAILED, detail, requestedBy, requestedByEmail, at)

        @Suppress("LongParameterList")
        fun rehydrate(
            id: UUID,
            deviceId: UUID,
            action: CpeActionType,
            status: CpeActionStatus,
            detail: String?,
            requestedBy: UUID,
            requestedByEmail: String?,
            requestedAt: Instant,
        ): CpeActionLog = CpeActionLog(id, deviceId, action, status, detail, requestedBy, requestedByEmail, requestedAt)
    }
}

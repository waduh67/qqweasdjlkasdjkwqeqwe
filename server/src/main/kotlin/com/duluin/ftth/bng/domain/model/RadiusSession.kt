package com.duluin.ftth.bng.domain.model

import com.duluin.ftth.common.domain.UuidV7
import java.time.Instant
import java.util.UUID

/**
 * Sesi PPPoE terkini sebuah akun jaringan — proyeksi keadaan yang dilaporkan BRAS
 * lewat collector, satu baris per akun (1 akun = 1 sesi PPPoE). Sumber "B-ras Check":
 * online/offline, IP framed, NAS, uptime.
 *
 * Ini BUKAN deret waktu: di-upsert tiap poll, hanya menyimpan keadaan terakhir.
 * Angka trafik (Mbps) sengaja TIDAK di sini melainkan dihitung dari deret akunting
 * ([AccountingRecordPoint]) agar tren bisa digambar. Ditaut ke akun/langganan/
 * pelanggan lewat UUID polos tanpa FK, menjaga batas modul.
 */
class RadiusSession private constructor(
    val id: UUID,
    val tenantId: UUID,
    val subscriberAccessId: UUID,
    val subscriptionId: UUID,
    val customerId: UUID,
    val username: String,
    nasId: UUID?,
    nasIp: String?,
    framedIp: String?,
    sessionId: String?,
    callingStationId: String?,
    online: Boolean,
    uptimeSeconds: Long?,
    startedAt: Instant?,
    lastSeenAt: Instant,
) {
    var nasId: UUID? = nasId
        private set

    var nasIp: String? = nasIp
        private set

    var framedIp: String? = framedIp
        private set

    var sessionId: String? = sessionId
        private set

    var callingStationId: String? = callingStationId
        private set

    var online: Boolean = online
        private set

    var uptimeSeconds: Long? = uptimeSeconds
        private set

    /** Perkiraan awal sesi = waktu poll − uptime; dipertahankan selama sesi sama. */
    var startedAt: Instant? = startedAt
        private set

    var lastSeenAt: Instant = lastSeenAt
        private set

    /**
     * Menyerap satu laporan poll. [startedAt] hanya bergeser bila [sessionId] berganti
     * (sesi baru) atau belum pernah dihitung — sehingga uptime sesi berjalan tidak
     * "meloncat" tiap poll akibat pembulatan.
     */
    @Suppress("LongParameterList")
    fun observe(
        online: Boolean,
        nasId: UUID?,
        nasIp: String?,
        framedIp: String?,
        sessionId: String?,
        callingStationId: String?,
        uptimeSeconds: Long?,
        observedAt: Instant,
    ) {
        if (this.sessionId != sessionId || this.startedAt == null) {
            this.startedAt = uptimeSeconds?.let { observedAt.minusSeconds(it) } ?: observedAt
        }
        this.online = online
        this.nasId = nasId
        this.nasIp = nasIp
        this.framedIp = framedIp
        this.sessionId = sessionId
        this.callingStationId = callingStationId
        this.uptimeSeconds = uptimeSeconds
        this.lastSeenAt = observedAt
    }

    /**
     * Sesi ini berakhir — BRAS tak lagi melaporkannya hidup.
     *
     * [lastSeenAt] SENGAJA tak digeser: nilainya berarti "terakhir terpantau hidup", dan
     * dari situlah layar menghitung "putus sejak kapan". Digeser tiap poll, sesi yang sudah
     * mati semalaman akan terus berbunyi "terakhir 30 detik lalu".
     *
     * Yang dikosongkan hanyalah milik sesi yang berjalan ([framedIp]/[uptimeSeconds]/
     * [startedAt]) — alamat IP sesi yang sudah tutup bukan lagi alamat pelanggan, dan
     * memajangnya di samping badge "Offline" hanya memancing salah baca. Jejak sesi
     * terakhir ([sessionId]/[nasIp]/[callingStationId]) tetap disimpan sebagai petunjuk.
     */
    fun markOffline() {
        online = false
        framedIp = null
        uptimeSeconds = null
        startedAt = null
    }

    companion object {
        @Suppress("LongParameterList")
        fun start(
            tenantId: UUID,
            subscriberAccessId: UUID,
            subscriptionId: UUID,
            customerId: UUID,
            username: String,
            online: Boolean,
            nasId: UUID?,
            nasIp: String?,
            framedIp: String?,
            sessionId: String?,
            callingStationId: String?,
            uptimeSeconds: Long?,
            observedAt: Instant,
        ): RadiusSession = RadiusSession(
            id = UuidV7.generate(),
            tenantId = tenantId,
            subscriberAccessId = subscriberAccessId,
            subscriptionId = subscriptionId,
            customerId = customerId,
            username = username,
            nasId = nasId,
            nasIp = nasIp,
            framedIp = framedIp,
            sessionId = sessionId,
            callingStationId = callingStationId,
            online = online,
            uptimeSeconds = uptimeSeconds,
            startedAt = uptimeSeconds?.let { observedAt.minusSeconds(it) } ?: observedAt,
            lastSeenAt = observedAt,
        )

        @Suppress("LongParameterList")
        fun rehydrate(
            id: UUID,
            tenantId: UUID,
            subscriberAccessId: UUID,
            subscriptionId: UUID,
            customerId: UUID,
            username: String,
            nasId: UUID?,
            nasIp: String?,
            framedIp: String?,
            sessionId: String?,
            callingStationId: String?,
            online: Boolean,
            uptimeSeconds: Long?,
            startedAt: Instant?,
            lastSeenAt: Instant,
        ): RadiusSession = RadiusSession(
            id, tenantId, subscriberAccessId, subscriptionId, customerId, username, nasId, nasIp, framedIp,
            sessionId, callingStationId, online, uptimeSeconds, startedAt, lastSeenAt,
        )
    }
}

package com.duluin.ftth.monitoring.domain.model

import com.duluin.ftth.common.domain.UuidV7
import com.duluin.ftth.common.domain.error.ConflictException
import com.duluin.ftth.common.domain.error.ValidationException
import java.time.Instant
import java.util.UUID

/** Tahap sebuah ONU terdeteksi dalam alur provisioning. */
enum class DiscoveredOnuState {
    /** Terlihat OLT tapi belum terdaftar — menunggu operator menuntaskannya. */
    DISCOVERED,

    /** Sudah ditautkan ke pelanggan & port ODP; hilang dari kotak masuk. */
    PROVISIONED,

    /** Sengaja diabaikan operator (mis. perangkat uji atau ONU tetangga). */
    IGNORED,
    ;

    /** Belum tuntas maupun diabaikan — masih menuntut keputusan. */
    val actionable: Boolean get() = this == DISCOVERED
}

/**
 * Sebuah ONU yang dilaporkan OLT tapi belum terdaftar di sistem — perangkat liar
 * yang terpasang di lapangan lebih dulu daripada dicatat operator.
 *
 * Alih-alih membuang serial tak dikenal ke log, tiap kali ingestion menemuinya ia
 * dirawat sebagai baris kotak masuk: satu baris per serial per tenant, diperbarui
 * (bukan digandakan) tiap siklus polling melihatnya lagi. Dari sini operator
 * cukup memilih pelanggan + port ODP untuk memprovisikannya, tanpa mengetik ulang
 * serial yang panjang dan rawan salah.
 */
class DiscoveredOnu private constructor(
    val id: UUID,
    val tenantId: UUID,
    val serialNumber: String,
    oltId: UUID?,
    oltCode: String,
    ponPortLabel: String?,
    lastStatus: String,
    lastRxPowerDbm: Double?,
    val firstSeenAt: Instant,
    lastSeenAt: Instant,
    seenCount: Int,
    state: DiscoveredOnuState,
) {
    /** OLT yang melaporkannya, bila kodenya sudah dikenal inventory. */
    var oltId: UUID? = oltId
        private set

    /** Kode OLT sebagaimana dilaporkan collector — dipertahankan meski belum ada di inventory. */
    var oltCode: String = oltCode
        private set

    var ponPortLabel: String? = ponPortLabel
        private set

    var lastStatus: String = lastStatus
        private set

    var lastRxPowerDbm: Double? = lastRxPowerDbm
        private set

    var lastSeenAt: Instant = lastSeenAt
        private set

    var seenCount: Int = seenCount
        private set

    var state: DiscoveredOnuState = state
        private set

    /**
     * Terlihat lagi di siklus polling berikutnya: perbarui pengamatan terakhirnya
     * dan naikkan pencacah. Tidak mengubah [state] — perangkat yang sudah diabaikan
     * tetap diabaikan meski masih menyala, dan yang sudah diprovisikan tak lagi
     * lewat sini karena serialnya kini dikenal.
     */
    fun observe(
        status: String,
        rxPowerDbm: Double?,
        oltId: UUID?,
        oltCode: String,
        ponPortLabel: String?,
        at: Instant,
    ) {
        if (state == DiscoveredOnuState.PROVISIONED) return
        this.lastStatus = status
        this.lastRxPowerDbm = rxPowerDbm
        this.oltId = oltId
        this.oltCode = oltCode.trim()
        this.ponPortLabel = ponPortLabel?.trim()?.ifBlank { null }
        if (at.isAfter(lastSeenAt)) this.lastSeenAt = at
        this.seenCount += 1
    }

    /** Operator (atau registrasi lewat jalur lain) telah menautkannya ke ONU nyata. */
    fun markProvisioned() {
        state = DiscoveredOnuState.PROVISIONED
    }

    /** Operator memutuskan mengabaikannya; keluar dari daftar yang menuntut tindakan. */
    fun ignore() {
        if (state == DiscoveredOnuState.PROVISIONED) {
            throw ConflictException("ONU $serialNumber sudah diprovisikan, tidak bisa diabaikan")
        }
        state = DiscoveredOnuState.IGNORED
    }

    companion object {
        @Suppress("LongParameterList")
        fun discover(
            tenantId: UUID,
            serialNumber: String,
            oltId: UUID?,
            oltCode: String,
            ponPortLabel: String?,
            lastStatus: String,
            lastRxPowerDbm: Double?,
            at: Instant = Instant.now(),
        ): DiscoveredOnu {
            val serial = serialNumber.trim().uppercase()
            if (serial.isBlank()) throw ValidationException("Serial ONU tidak boleh kosong")
            return DiscoveredOnu(
                id = UuidV7.generate(),
                tenantId = tenantId,
                serialNumber = serial,
                oltId = oltId,
                oltCode = oltCode.trim(),
                ponPortLabel = ponPortLabel?.trim()?.ifBlank { null },
                lastStatus = lastStatus,
                lastRxPowerDbm = lastRxPowerDbm,
                firstSeenAt = at,
                lastSeenAt = at,
                seenCount = 1,
                state = DiscoveredOnuState.DISCOVERED,
            )
        }

        @Suppress("LongParameterList")
        fun rehydrate(
            id: UUID,
            tenantId: UUID,
            serialNumber: String,
            oltId: UUID?,
            oltCode: String,
            ponPortLabel: String?,
            lastStatus: String,
            lastRxPowerDbm: Double?,
            firstSeenAt: Instant,
            lastSeenAt: Instant,
            seenCount: Int,
            state: DiscoveredOnuState,
        ) = DiscoveredOnu(
            id, tenantId, serialNumber, oltId, oltCode, ponPortLabel, lastStatus,
            lastRxPowerDbm, firstSeenAt, lastSeenAt, seenCount, state,
        )
    }
}

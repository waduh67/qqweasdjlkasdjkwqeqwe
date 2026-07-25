package com.duluin.ftth.cpe.domain.model

import com.duluin.ftth.common.domain.UuidV7
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Proyeksi tipis sebuah CPE — router/ONT di rumah pelanggan yang dikelola
 * GenieACS lewat TR-069. Bukan sumber kebenaran: hanya cermin yang disinkronkan
 * berkala dari ACS supaya UI bisa menampilkan daftar & status tanpa memanggil NBI
 * pada tiap render.
 *
 * Data yang cepat basi — jaringan WiFi, host tersambung — sengaja TIDAK disimpan
 * di sini. Itu dibaca langsung dari ACS saat panel dibuka agar selalu terkini;
 * menyimpannya hanya akan menyajikan salinan usang.
 *
 * Ditautkan ke pelanggan lewat serial ONU: device yang serialnya cocok dengan ONU
 * terdaftar diikat ke pelanggan pemilik ONU itu. Rujukan lintas-module disimpan
 * sebagai uuid polos ([customerId], [onuId]) — tanpa foreign key, sama seperti
 * `entity_id` pada alarm.
 */
class CpeDevice private constructor(
    val id: UUID,
    /** Id internal GenieACS (`_id`), kunci untuk setiap perintah NBI. Tak berubah. */
    val genieacsId: String,
    val serialNumber: String,
    oui: String?,
    productClass: String?,
    manufacturer: String?,
    model: String?,
    softwareVersion: String?,
    ipAddress: String?,
    lastInformAt: Instant?,
    customerId: UUID?,
    onuId: UUID?,
) {
    var oui: String? = oui
        private set
    var productClass: String? = productClass
        private set
    var manufacturer: String? = manufacturer
        private set
    var model: String? = model
        private set
    var softwareVersion: String? = softwareVersion
        private set
    var ipAddress: String? = ipAddress
        private set
    var lastInformAt: Instant? = lastInformAt
        private set
    var customerId: UUID? = customerId
        private set
    var onuId: UUID? = onuId
        private set

    /**
     * Perbarui atribut dari snapshot ACS terbaru. Identitas ([genieacsId],
     * [serialNumber]) tetap — yang berubah hanya keadaan yang dilaporkan perangkat.
     */
    fun applySnapshot(
        oui: String?,
        productClass: String?,
        manufacturer: String?,
        model: String?,
        softwareVersion: String?,
        ipAddress: String?,
        lastInformAt: Instant?,
    ) {
        this.oui = oui
        this.productClass = productClass
        this.manufacturer = manufacturer
        this.model = model
        this.softwareVersion = softwareVersion
        this.ipAddress = ipAddress
        this.lastInformAt = lastInformAt
    }

    /** Tautkan ke pelanggan & ONU-nya, hasil pencocokan serial saat sinkronisasi. */
    fun linkTo(customerId: UUID, onuId: UUID?) {
        this.customerId = customerId
        this.onuId = onuId
    }

    /**
     * Online bila ACS masih menerima inform dalam ambang [staleAfter]. TR-069
     * memakai model "inform berkala"; ketiadaan inform terkini berarti perangkat
     * mati atau kehilangan koneksi — tidak ada sinyal keep-alive lain untuk dilihat.
     */
    fun isOnline(now: Instant, staleAfter: Duration): Boolean {
        val seen = lastInformAt ?: return false
        return !Duration.between(seen, now).isNegative && Duration.between(seen, now) <= staleAfter
    }

    companion object {
        /** Proyeksi baru untuk device yang serialnya cocok dengan ONU pelanggan. */
        @Suppress("LongParameterList")
        fun link(
            genieacsId: String,
            serialNumber: String,
            oui: String?,
            productClass: String?,
            manufacturer: String?,
            model: String?,
            softwareVersion: String?,
            ipAddress: String?,
            lastInformAt: Instant?,
            customerId: UUID,
            onuId: UUID?,
        ): CpeDevice = CpeDevice(
            id = UuidV7.generate(),
            genieacsId = genieacsId,
            serialNumber = serialNumber,
            oui = oui,
            productClass = productClass,
            manufacturer = manufacturer,
            model = model,
            softwareVersion = softwareVersion,
            ipAddress = ipAddress,
            lastInformAt = lastInformAt,
            customerId = customerId,
            onuId = onuId,
        )

        @Suppress("LongParameterList")
        fun rehydrate(
            id: UUID,
            genieacsId: String,
            serialNumber: String,
            oui: String?,
            productClass: String?,
            manufacturer: String?,
            model: String?,
            softwareVersion: String?,
            ipAddress: String?,
            lastInformAt: Instant?,
            customerId: UUID?,
            onuId: UUID?,
        ): CpeDevice = CpeDevice(
            id, genieacsId, serialNumber, oui, productClass, manufacturer,
            model, softwareVersion, ipAddress, lastInformAt, customerId, onuId,
        )
    }
}

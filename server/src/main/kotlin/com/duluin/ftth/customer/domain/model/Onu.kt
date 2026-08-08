package com.duluin.ftth.customer.domain.model

import com.duluin.ftth.common.domain.UuidV7
import com.duluin.ftth.common.domain.error.ConflictException
import com.duluin.ftth.common.domain.error.ValidationException
import java.time.Instant
import java.util.UUID

enum class OnuStatus {
    /** Terdaftar, belum dipasang di lapangan. */
    PENDING,
    ONLINE,
    OFFLINE,
    /** Loss of Signal — fiber putus atau konektor lepas. */
    LOS,
    DISMANTLED,
}

/**
 * Kualitas daya terima optik ONU. Ambangnya adalah praktik lapangan GPON yang
 * lazim: di bawah -8 dBm terlalu terang (bisa merusak penerima), di bawah
 * -27 dBm sudah di ambang sensitivitas.
 */
enum class OpticalHealth {
    GOOD,
    WARNING,
    CRITICAL,
    UNKNOWN,
    ;

    companion object {
        const val OVERLOAD_DBM = -8.0
        const val WARNING_DBM = -25.0
        const val CRITICAL_DBM = -27.0

        fun of(rxPowerDbm: Double?): OpticalHealth = when {
            rxPowerDbm == null -> UNKNOWN
            rxPowerDbm > OVERLOAD_DBM -> CRITICAL
            rxPowerDbm < CRITICAL_DBM -> CRITICAL
            rxPowerDbm < WARNING_DBM -> WARNING
            else -> GOOD
        }
    }
}

/**
 * Perangkat ONU/ONT milik pelanggan dan penempatannya di port ODP.
 *
 * Aturan "satu port satu ONU" tidak dijaga di sini melainkan oleh module network
 * (pemilik ODP) plus indeks unik di database. Agregat ini hanya menjaga bahwa
 * penempatannya utuh — ODP dan nomor port selalu terisi bersamaan atau kosong
 * bersamaan, tidak pernah separuh.
 */
class Onu private constructor(
    val id: UUID,
    val tenantId: UUID,
    val customerId: UUID,
    val serialNumber: String,
    odpId: UUID?,
    odpPortNumber: Int?,
    model: String?,
    installRxPowerDbm: Double?,
    status: OnuStatus,
    installedAt: Instant?,
) {
    var odpId: UUID? = odpId
        private set

    var odpPortNumber: Int? = odpPortNumber
        private set

    var model: String? = model
        private set

    var installRxPowerDbm: Double? = installRxPowerDbm
        private set

    var status: OnuStatus = status
        private set

    var installedAt: Instant? = installedAt
        private set

    val attached: Boolean get() = odpId != null

    fun opticalHealth(): OpticalHealth = OpticalHealth.of(installRxPowerDbm)

    /**
     * Memasang ONU ke sebuah port ODP. Kelayakan portnya sudah divalidasi module
     * network sebelum metode ini dipanggil.
     */
    fun attachTo(odpId: UUID, portNumber: Int, rxPowerDbm: Double?, at: Instant = Instant.now()) {
        if (status == OnuStatus.DISMANTLED) {
            throw ConflictException("ONU $serialNumber sudah dibongkar dan tidak bisa dipasang ulang")
        }
        this.odpId = odpId
        this.odpPortNumber = portNumber
        if (rxPowerDbm != null) this.installRxPowerDbm = validateRxPower(rxPowerDbm)
        if (installedAt == null) installedAt = at
        // Status SENGAJA tak disentuh: ia cuma boleh lahir dari pengamatan nyata
        // (polling SNMP OLT lewat recordObservedOnuStatuses) atau tindakan operator.
        // Dulu memasang ONU langsung menjadikannya OFFLINE, padahal belum pernah
        // dipantau sama sekali — operator jadi melihat "Offline" yang meyakinkan
        // untuk ONU yang sebetulnya tak diketahui kabarnya (mis. OLT belum
        // dikonfigurasi SNMP, atau serialnya tak pernah muncul di walk). PENDING
        // dibaca sebagai "belum terpantau", dan polling pertama akan menimpanya.
    }

    /** Melepas ONU dari ODP, mis. saat pelanggan pindah rumah atau ODP diganti. */
    fun detach() {
        odpId = null
        odpPortNumber = null
    }

    fun updateDevice(model: String?) {
        this.model = model?.trim()?.takeIf { it.isNotEmpty() }
    }

    fun changeStatus(status: OnuStatus) {
        this.status = status
        if (status == OnuStatus.DISMANTLED) detach()
    }

    companion object {
        private val SERIAL_PATTERN = Regex("^[A-Za-z0-9-]{4,60}$")

        fun create(
            tenantId: UUID,
            customerId: UUID,
            serialNumber: String,
            model: String?,
        ): Onu = Onu(
            id = UuidV7.generate(),
            tenantId = tenantId,
            customerId = customerId,
            serialNumber = validateSerial(serialNumber),
            odpId = null,
            odpPortNumber = null,
            model = model?.trim()?.takeIf { it.isNotEmpty() },
            installRxPowerDbm = null,
            status = OnuStatus.PENDING,
            installedAt = null,
        )

        @Suppress("LongParameterList")
        fun rehydrate(
            id: UUID,
            tenantId: UUID,
            customerId: UUID,
            serialNumber: String,
            odpId: UUID?,
            odpPortNumber: Int?,
            model: String?,
            installRxPowerDbm: Double?,
            status: OnuStatus,
            installedAt: Instant?,
        ): Onu = Onu(
            id, tenantId, customerId, serialNumber, odpId, odpPortNumber, model, installRxPowerDbm, status, installedAt,
        )

        private fun validateSerial(serial: String): String {
            val normalized = serial.trim().uppercase()
            if (!SERIAL_PATTERN.matches(normalized)) {
                throw ValidationException("Serial number ONU '$serial' tidak valid: 4-60 karakter alfanumerik")
            }
            return normalized
        }

        private fun validateRxPower(dbm: Double): Double {
            if (dbm !in -40.0..0.0) throw ValidationException("Redaman terima harus antara -40 dan 0 dBm")
            return dbm
        }
    }
}

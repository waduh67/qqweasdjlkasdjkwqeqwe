package com.duluin.ftth.monitoring.application.port.inbound

import com.duluin.ftth.monitoring.domain.model.DiscoveredOnuState
import java.time.Instant
import java.util.UUID

/**
 * Kotak masuk provisioning: menampilkan ONU liar yang terdeteksi jaringan dan
 * menuntaskannya menjadi pelanggan terpasang.
 *
 * Penautan sebenarnya (mendaftarkan ONU + memasang ke port ODP) milik module
 * customer; module monitoring hanya mengorkestrasi lewat `CustomerApi` lalu
 * menandai baris kotak masuknya selesai.
 */
interface ManageDiscoveredOnuUseCase {

    /** Daftar ONU terdeteksi pada sebuah tahap; default yang masih menunggu tindakan. */
    fun list(state: DiscoveredOnuState?): List<DiscoveredOnuView>

    /**
     * Menuntaskan sebuah ONU terdeteksi: daftarkan serialnya untuk pelanggan lalu
     * pasang ke port ODP yang dipilih. Baris kotak masuknya ditandai PROVISIONED.
     */
    fun provision(id: UUID, command: ProvisionDiscoveredOnuCommand): DiscoveredOnuView

    /** Mengabaikan sebuah ONU terdeteksi (perangkat uji, ONU tetangga, dst). */
    fun ignore(id: UUID): DiscoveredOnuView
}

data class ProvisionDiscoveredOnuCommand(
    val customerId: UUID,
    val odpId: UUID,
    val portNumber: Int,
    /** Redaman baseline saat instalasi; bila null dipakai redaman terakhir yang teramati. */
    val installRxPowerDbm: Double?,
)

data class DiscoveredOnuView(
    val id: UUID,
    val serialNumber: String,
    val oltId: UUID?,
    val oltCode: String,
    val ponPortLabel: String?,
    val lastStatus: String,
    val lastRxPowerDbm: Double?,
    val firstSeenAt: Instant,
    val lastSeenAt: Instant,
    val seenCount: Int,
    val state: DiscoveredOnuState,
)

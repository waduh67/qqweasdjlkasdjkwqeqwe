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
    /**
     * Tebakan auto-link {pelanggan, ODP, port} dari topologi + backlog instalasi,
     * agar operator cukup mengonfirmasi. `null` untuk baris yang tak lagi menunggu
     * tindakan (sudah diprovisikan/diabaikan).
     */
    val suggestion: ProvisioningSuggestion? = null,
)

/** Seberapa yakin saran auto-link, menentukan cara UI menyajikannya. */
enum class SuggestionConfidence {
    /** Cocok tunggal: pelanggan menunggu instalasi + ODP + port jelas — layak 1-klik. */
    HIGH,

    /** Pelanggan & ODP tertebak tapi ada alternatif — pra-isi, tapi minta operator memeriksa. */
    MEDIUM,

    /** Hanya ODP + port yang bisa ditebak dari topologi; pelanggan dipilih manual. */
    LOW,

    /** Tak ada yang bisa ditebak (PON port belum terpetakan / OLT belum dikenal). */
    NONE,
}

/**
 * Saran penautan sebuah ONU liar. Field bernilai `null` berarti bagian itu tak
 * bisa ditebak dan harus diisi operator; [reason] selalu menjelaskan alasannya.
 */
data class ProvisioningSuggestion(
    val confidence: SuggestionConfidence,
    val customerId: UUID?,
    val customerName: String?,
    val odpId: UUID?,
    val odpCode: String?,
    val portNumber: Int?,
    val reason: String,
) {
    /** Lengkap untuk 1-klik: pelanggan, ODP, dan port sudah tertebak. */
    val complete: Boolean get() = customerId != null && odpId != null && portNumber != null
}

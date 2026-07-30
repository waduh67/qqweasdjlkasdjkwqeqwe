package com.duluin.ftth.cpe

import java.time.Instant
import java.util.UUID

/**
 * Kontrak publik modul `cpe` untuk modul lain (mis. aggregator Subscriber-360).
 * Menjawab per-PELANGGAN: status ringkas CPE (router/ONT) yang dikelola GenieACS.
 *
 * Berbeda dari [com.duluin.ftth.cpe.application.port.inbound.CpeQuery] yang melayani UI
 * modul cpe sendiri (view kaya + live state dari ACS), kontrak ini hanya membocorkan
 * status proyeksi tersimpan — tak pernah memanggil ACS, jadi murah dipanggil dari
 * agregator. Cpe "sink": tak memanggil balik pemanggilnya.
 */
interface CpeApi {

    /**
     * Status ringkas semua CPE milik pelanggan; [CpeDeviceStatusRef.online] dihitung
     * saat query dari inform terakhir. Kosong bila pelanggan belum punya CPE terpaut.
     */
    fun findDevicesForCustomer(customerId: UUID): List<CpeDeviceStatusRef>
}

/**
 * Pandangan lintas-modul status satu CPE. [online] dihitung dari [lastInformAt]
 * terhadap ambang basi saat query (bukan tersimpan). Tanpa data cepat-basi (WiFi/host)
 * yang butuh panggilan ACS langsung.
 */
data class CpeDeviceStatusRef(
    val deviceId: UUID,
    val serialNumber: String,
    val manufacturer: String?,
    val model: String?,
    val softwareVersion: String?,
    val ipAddress: String?,
    val lastInformAt: Instant?,
    val online: Boolean,
)

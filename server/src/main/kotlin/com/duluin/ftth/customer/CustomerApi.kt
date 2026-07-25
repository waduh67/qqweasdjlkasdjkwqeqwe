package com.duluin.ftth.customer

import com.duluin.ftth.common.domain.geo.Coordinate
import java.util.UUID

/**
 * Kontrak publik module customer untuk module lain (gis saat menyusun panel ODP
 * dan telusur jalur; nanti incident saat menghitung pelanggan terdampak).
 */
interface CustomerApi {

    fun findCustomer(id: UUID): CustomerRef?

    /** Resolusi sekumpulan id pelanggan sekaligus; id yang tak ditemukan diabaikan. */
    fun findCustomersByIds(ids: Set<UUID>): List<CustomerRef>

    /** Semua pelanggan yang tersambung pada sebuah ODP, terurut menurut nomor port. */
    fun findOccupantsOfOdp(odpId: UUID): List<OdpOccupant>

    /**
     * Penempatan fisik seorang pelanggan (ODP mana, port berapa, ONU apa).
     * `null` bila pelanggan belum punya ONU yang terpasang — kondisi normal untuk
     * pelanggan yang baru didaftarkan dan menunggu instalasi.
     */
    fun findPlacementOf(customerId: UUID): CustomerPlacement?

    /** Nomor port yang sudah terpakai pada sebuah ODP. */
    fun occupiedPortsOn(odpId: UUID): Set<Int>

    /** Jumlah ONU terpasang per ODP dalam satu query — untuk heatmap utilisasi peta. */
    fun countOccupantsByOdp(odpIds: Set<UUID>): Map<UUID, Long>

    /** Vector tile berisi layer `customer`; digabung module `gis` dengan layer jaringan. */
    fun renderMapTile(z: Int, x: Int, y: Int, areaIds: Set<UUID>?): ByteArray

    /**
     * Memetakan serial yang dilaporkan OLT ke ONU yang terdaftar. Serial yang
     * tidak ada di hasil berarti perangkat liar — terpasang di lapangan tapi
     * belum pernah didaftarkan.
     */
    fun findOnusBySerialNumbers(serialNumbers: Set<String>): List<OnuRef>

    /**
     * Penempatan sekumpulan ONU (ke pelanggan & ODP mana), untuk memetakan alarm
     * ONU ke kabel yang terdampak. ONU yang tidak ditemukan diabaikan.
     */
    fun placementsForOnus(onuIds: Set<UUID>): List<OnuPlacementRef>

    /**
     * Menerapkan status ONU yang teramati dari jaringan.
     *
     * Module monitoring TIDAK menulis ke agregat ONU secara langsung; ia melapor
     * lewat kontrak ini dan module customer yang memutuskan. Hanya baris yang
     * statusnya benar-benar berubah yang ditulis — satu siklus polling membawa
     * ribuan bacaan dan hampir semuanya tidak berubah.
     *
     * @return jumlah ONU yang statusnya berubah.
     */
    fun recordObservedOnuStatuses(statuses: Map<UUID, String>): Int

    /**
     * Memprovisikan sebuah ONU dari perangkat yang terdeteksi jaringan: daftarkan
     * serialnya untuk pelanggan (atau pakai ulang bila sudah terdaftar untuk
     * pelanggan yang sama) lalu pasang ke port ODP. Aturan port tetap ditegakkan
     * network. Dipakai module monitoring saat operator menuntaskan ONU dari kotak
     * masuk provisioning.
     *
     * @throws com.duluin.ftth.common.domain.error.ConflictException bila serial sudah terdaftar untuk pelanggan lain
     */
    fun provisionOnu(command: ProvisionOnuCommand): OnuRef
}

/** Perintah memprovisikan ONU liar menjadi pelanggan terpasang. */
data class ProvisionOnuCommand(
    val serialNumber: String,
    val model: String?,
    val customerId: UUID,
    val odpId: UUID,
    val portNumber: Int,
    /** Redaman baseline saat instalasi untuk deteksi degradasi; boleh null. */
    val installRxPowerDbm: Double?,
)

/** Pandangan ringkas sebuah ONU untuk konsumen lintas-module. */
data class OnuRef(
    val id: UUID,
    val serialNumber: String,
    val customerId: UUID,
    val customerName: String,
    val odpId: UUID?,
    val status: String,
)

data class CustomerRef(
    val id: UUID,
    val code: String,
    val name: String,
    val phone: String?,
    val location: Coordinate,
    val status: String,
)

/** Penempatan sebuah ONU: milik pelanggan mana dan di ODP mana (bila terpasang). */
data class OnuPlacementRef(
    val onuId: UUID,
    val customerId: UUID,
    val odpId: UUID?,
)

/** Di mana ONU seorang pelanggan terpasang, beserta kondisi optiknya. */
data class CustomerPlacement(
    val odpId: UUID,
    val portNumber: Int,
    val onuSerialNumber: String,
    val onuStatus: String,
    val opticalHealth: String,
    val installRxPowerDbm: Double?,
)

/**
 * Satu pelanggan yang menempati port ODP — gabungan data pelanggan, ONU, dan
 * langganannya. Bentuk inilah yang menjawab pertanyaan lapangan "di ODP ini ada
 * siapa saja dan statusnya apa".
 */
data class OdpOccupant(
    val portNumber: Int,
    val customerId: UUID,
    val customerCode: String,
    val customerName: String,
    val phone: String?,
    val location: Coordinate,
    /** Id ONU penghuni — untuk memadukan dengan bacaan hidup monitoring per ONU. */
    val onuId: UUID,
    val onuSerialNumber: String,
    val onuStatus: String,
    val opticalHealth: String,
    val installRxPowerDbm: Double?,
    val subscriptionPackage: String?,
    val subscriptionStatus: String?,
)

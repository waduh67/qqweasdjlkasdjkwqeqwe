package com.duluin.ftth.customer

import com.duluin.ftth.common.domain.geo.Coordinate
import java.util.UUID

/**
 * Kontrak publik module customer untuk module lain (gis saat menyusun panel ODP
 * dan telusur jalur; nanti incident saat menghitung pelanggan terdampak).
 */
interface CustomerApi {

    fun findCustomer(id: UUID): CustomerRef?

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
}

data class CustomerRef(
    val id: UUID,
    val code: String,
    val name: String,
    val phone: String?,
    val location: Coordinate,
    val status: String,
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
    val onuSerialNumber: String,
    val onuStatus: String,
    val opticalHealth: String,
    val installRxPowerDbm: Double?,
    val subscriptionPackage: String?,
    val subscriptionStatus: String?,
)

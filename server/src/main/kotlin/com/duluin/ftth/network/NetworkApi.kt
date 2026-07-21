package com.duluin.ftth.network

import com.duluin.ftth.common.domain.geo.Coordinate
import java.util.UUID

/**
 * Kontrak publik module network untuk module lain (customer saat memasang ONU,
 * gis saat menyusun peta & telusur jalur).
 *
 * Sengaja mengekspos [OdpRef] dan bukan agregat `Odp`: module lain tidak boleh
 * mengubah state jaringan langsung, hanya membacanya dan meminta network
 * menegakkan aturannya sendiri lewat [assertOdpPortAssignable].
 */
interface NetworkApi {

    fun findOdp(id: UUID): OdpRef?

    fun requireOdp(id: UUID): OdpRef

    fun findOdpsByIds(ids: Set<UUID>): List<OdpRef>

    /**
     * Menegakkan aturan penempatan ONU pada port ODP. Okupansi disuplai pemanggil
     * karena data ONU dimiliki module customer — dengan begitu aturannya tetap
     * tinggal di network tanpa membuat ketergantungan melingkar.
     *
     * @throws com.duluin.ftth.common.domain.error.ConflictException bila port terpakai atau ODP belum aktif
     * @throws com.duluin.ftth.common.domain.error.ValidationException bila nomor port di luar kapasitas
     */
    fun assertOdpPortAssignable(odpId: UUID, portNumber: Int, occupiedPorts: Set<Int>)

    /** Jalur hulu sebuah ODP: ODP → ODC → PON port → OLT → site, untuk telusur & korelasi alarm. */
    fun upstreamOf(odpId: UUID): UpstreamPath

    /**
     * Vector tile berisi layer `site`, `odc`, `odp`, dan `cable`.
     *
     * Setiap module merender layernya sendiri dan module `gis` menggabungkannya,
     * sehingga tidak ada module yang perlu membaca tabel milik module lain.
     */
    fun renderMapTile(z: Int, x: Int, y: Int, areaIds: Set<UUID>?): ByteArray

    /** Memetakan kode OLT yang dilaporkan collector kembali ke OLT di inventory. */
    fun findOltByCode(code: String): OltRef?

    fun findOltsByIds(ids: Set<UUID>): List<OltRef>

    fun listAllOltIds(): Set<UUID>

    /**
     * Data yang dibutuhkan collector untuk mem-polling sekumpulan OLT, TERMASUK
     * community string SNMP dalam bentuk terbaca.
     *
     * Dipisahkan dari [findOltsByIds] dan dinamai eksplisit supaya jelas bahwa
     * ini satu-satunya jalan kredensial perangkat keluar dari module network.
     * Pemanggilnya hanya endpoint collector yang sudah terautentikasi; API
     * pengguna biasa tidak pernah menyentuhnya.
     */
    fun findPollingTargets(oltIds: Set<UUID>): List<OltPollingTarget>

    /**
     * Kabel yang salah satu ujungnya menyentuh sebuah simpul dalam [nodeIds],
     * beserta geometrinya. Dipakai gis untuk mewarnai kabel yang hilirnya
     * bermasalah tanpa module lain menyentuh tabel kabel langsung.
     */
    fun cablesTouchingNodes(nodeIds: Set<UUID>): List<CablePath>
}

/** Kabel beserta jalur geometrinya untuk kebutuhan overlay peta. */
data class CablePath(
    val id: UUID,
    val code: String,
    val cableType: String,
    val points: List<Coordinate>,
    val fromId: UUID,
    val toId: UUID,
)

/** Pandangan ringkas sebuah OLT untuk konsumen lintas-module. */
data class OltRef(
    val id: UUID,
    val code: String,
    val name: String,
    val vendor: String,
    val siteId: UUID,
    val active: Boolean,
)

/** OLT beserta kredensialnya, khusus untuk collector. */
data class OltPollingTarget(
    val id: UUID,
    val code: String,
    val vendor: String,
    val host: String?,
    val snmpCommunity: String?,
) {
    /** Tanpa alamat, collector tidak punya apa pun untuk dihubungi. */
    val pollable: Boolean get() = !host.isNullOrBlank()
}

/** Pandangan ringkas sebuah ODP untuk konsumen lintas-module. */
data class OdpRef(
    val id: UUID,
    val code: String,
    val name: String,
    val location: Coordinate,
    val capacity: Int,
    val areaId: UUID?,
    val odcId: UUID?,
    val active: Boolean,
)

/**
 * Rantai perangkat di hulu sebuah ODP. Setiap tingkat bisa `null` bila jaringan
 * belum tersambung penuh — kondisi normal saat pembangunan masih berjalan, dan
 * justru informasi yang berguna untuk ditampilkan.
 */
data class UpstreamPath(
    val odp: OdpRef,
    val odc: UpstreamHop?,
    val ponPort: UpstreamHop?,
    val olt: UpstreamHop?,
    val site: UpstreamHop?,
    /** Total rugi sisipan splitter di sepanjang jalur, dalam dB. */
    val splitterLossDb: Double,
) {
    /** Jalur lengkap sampai OLT — prasyarat monitoring otomatis di Phase 2. */
    val complete: Boolean get() = odc != null && ponPort != null && olt != null
}

data class UpstreamHop(val id: UUID, val code: String, val name: String)

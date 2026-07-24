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

    /** Ringkasan sebuah ODC, untuk header panel blast radius ("siapa di bawah ODC ini"). */
    fun findOdc(id: UUID): OdcRef?

    fun requireOdc(id: UUID): OdcRef

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

    /** Ringkasan sebuah site/POP, untuk header panel inspeksi site di peta. */
    fun findSite(id: UUID): SiteRef?

    /** OLT yang berada di sebuah site — untuk menghitung hilir & mengisi panel site. */
    fun oltsAtSite(siteId: UUID): List<OltRef>

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

    /**
     * Memperluas alarm perangkat hulu ke perangkat di bawahnya (blast radius):
     * OLT → ODC di bawahnya → ODP di bawahnya. Dipakai gis agar OLT/ODC yang mati
     * ikut menyorot merah seluruh feeder, distribusi, dan drop di hilirnya.
     */
    fun downstreamDeviceIds(oltIds: Set<UUID>, odcIds: Set<UUID>): DownstreamIds

    /**
     * Dampak topologis bila sebuah kabel diputus: seluruh subpohon fisik di hilir
     * ujung bawahnya. Dipakai gis untuk simulasi "kalau kabel ini putus, siapa
     * yang kena".
     *
     * Sebuah putus memutus segala yang dialiri lewatnya. Ujung hilir (`to`) kabel
     * menjadi akar subpohon yang lenyap; dari sana network menelusuri graf kabel
     * ke bawah — menangkap ODP berantai maupun drop yang tergambar, apa pun jenis
     * kabelnya. Untuk akar berupa ODC, daftar ODP dilengkapi lewat pohon perangkat
     * (`odp.odcId`) karena keanggotaan ODP di bawah ODC dicatat secara logis, tak
     * selalu digambar sebagai kabel distribusi.
     */
    fun cutImpact(cableId: UUID): CableCutImpact
}

/**
 * Subpohon yang lenyap bila sebuah kabel diputus, dalam istilah topologi murni.
 * Module gis menerjemahkannya menjadi daftar pelanggan terdampak.
 */
data class CableCutImpact(
    val cableId: UUID,
    val cableCode: String,
    val cableType: String,
    /** Jenis simpul di ujung hilir kabel — akar subpohon yang terputus. */
    val severedRootKind: String,
    val severedRootId: UUID,
    /** ODC di hilir yang ikut kehilangan uplink (relevan untuk putus feeder). */
    val odcIds: Set<UUID>,
    /** ODP di hilir yang kehilangan uplink. */
    val odpIds: Set<UUID>,
    /** Pelanggan yang tersambung langsung lewat kabel drop yang terputus. */
    val customerIds: Set<UUID>,
    /** Ruas kabel dalam subpohon terputus (termasuk kabel yang diputus) untuk disorot di peta. */
    val severedCables: List<CablePath>,
)

/** Perangkat hilir dari sekumpulan OLT/ODC yang bermasalah. */
data class DownstreamIds(val odcIds: Set<UUID>, val odpIds: Set<UUID>)

/** Kabel beserta jalur geometrinya untuk kebutuhan overlay peta. */
data class CablePath(
    val id: UUID,
    val code: String,
    val cableType: String,
    val points: List<Coordinate>,
    val fromId: UUID,
    val toId: UUID,
)

/** Pandangan ringkas sebuah site/POP untuk konsumen lintas-module. */
data class SiteRef(
    val id: UUID,
    val code: String,
    val name: String,
    val address: String?,
    val location: Coordinate,
    val areaId: UUID?,
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

/** Pandangan ringkas sebuah ODC untuk konsumen lintas-module. */
data class OdcRef(
    val id: UUID,
    val code: String,
    val name: String,
    val location: Coordinate,
    val capacity: Int,
    val ponPortId: UUID?,
    /** Aktif DAN punya uplink — ODC tanpa uplink tak mengalirkan layanan meski "aktif". */
    val energized: Boolean,
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

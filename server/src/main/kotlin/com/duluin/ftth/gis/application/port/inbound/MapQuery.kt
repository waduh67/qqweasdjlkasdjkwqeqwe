package com.duluin.ftth.gis.application.port.inbound

import com.duluin.ftth.common.domain.geo.Coordinate
import com.duluin.ftth.customer.OdpOccupant
import java.time.Instant
import java.util.UUID

/**
 * Pandangan peta dan telusur jalur — satu-satunya tempat data jaringan dan data
 * pelanggan dipertemukan.
 *
 * Module `gis` sengaja tidak punya tabel sendiri: ia menyusun jawaban dari
 * `NetworkApi` dan `CustomerApi`. Dengan begitu pertanyaan lintas-domain seperti
 * "siapa saja di ODP ini" bisa dijawab tanpa membuat network dan customer saling
 * bergantung.
 */
interface MapQuery {

    fun renderTile(z: Int, x: Int, y: Int): ByteArray

    /** Isi lengkap sebuah ODP: hulu, okupansi port, dan daftar pelanggannya. */
    fun inspectOdp(odpId: UUID): OdpInspection

    /** Telusur jalur dari pelanggan sampai OLT, lengkap dengan anggaran redaman. */
    fun traceCustomer(customerId: UUID): CustomerTrace

    /**
     * Tetangga seorang pelanggan: penghuni ODP yang sama, dan penghuni seluruh ODP
     * di bawah PON port yang sama — masing-masing dengan bacaan hidup ONU-nya.
     * Menjawab pertanyaan lapangan "siapa lagi di jalur yang sama" saat menangani
     * gangguan: kalau semua tetangga se-PON ikut mati, masalahnya di hulu.
     */
    fun subscriberNeighbors(customerId: UUID): SubscriberNeighbors

    /**
     * Kabel yang hilirnya sedang bermasalah menurut alarm hidup, untuk disorot
     * merah di peta ("perangkat modar → kabel merah"). Menyusun dari monitoring
     * (alarm) + customer (ONU→pelanggan/ODP) + network (geometri kabel).
     */
    fun impactedCables(): ImpactedOverlay

    /**
     * Blast radius sebuah ODC: seluruh pelanggan di hilirnya lewat ODP-ODP-nya —
     * menjawab "kalau perangkat ini putus, siapa yang kena" dan menyiapkan daftar
     * sasaran broadcast pemberitahuan proaktif.
     */
    fun blastRadius(odcId: UUID): BlastRadiusView

    /**
     * Simulasi "kalau kabel ini putus": pelanggan yang kehilangan layanan di hilir
     * ujung bawah kabel, plus geometri kabel yang lenyap untuk disorot di peta.
     * Menyusun dari topologi network (subpohon terputus) dan data pelanggan
     * (okupansi ODP di hilir) — tanpa module mana pun menyentuh tabel module lain.
     */
    fun cutBlastRadius(cableId: UUID): CableCutView

    /**
     * Isi sebuah site/POP untuk panel peta: OLT yang berdiri di sini plus rekap
     * seluruh perangkat & pelanggan di hilirnya — "seberapa besar site ini".
     */
    fun inspectSite(siteId: UUID): SiteInspection

    /**
     * Heatmap utilisasi port untuk perencanaan kapasitas: tiap ODP dalam batasan
     * area pengguna beserta kapasitas, port terpakai, dan persentasenya. Menyusun
     * dari network (lokasi & kapasitas ODP) dan customer (jumlah okupansi per ODP,
     * satu query hitung agregat) — tanpa module mana pun menyentuh tabel module lain.
     */
    fun utilizationHeatmap(): UtilizationHeatmap

    /**
     * Drill-down sebuah PON port OLT: ODC yang menggantung padanya, tiap ODC dengan
     * ODP (FAT) anaknya, lengkap utilisasi port di tiap tingkat. Menyusun dari network
     * (topologi PON → ODC → ODP) dan customer (okupansi per ODP dalam satu query hitung
     * agregat) — tanpa module mana pun menyentuh tabel module lain. Angka okupansi
     * bersifat agregat (non-PII); drill ke satu ODP daun memakai [inspectOdp] yang
     * sudah ada untuk melihat daftar penghuninya.
     */
    fun inspectPonPort(ponPortId: UUID): PonPortInspection

    /**
     * ONU (pelanggan) terpasang di bawah sebuah OLT — pandangan per-OLT untuk halaman
     * detail OLT ("ONU siapa saja yang menggantung di OLT ini"). Menyusun dari network
     * (ODP di bawah OLT lewat primitif downstreamDeviceIds) dan customer (penghuni ODP-ODP
     * itu dalam satu batch, tiga query tetap) — tanpa module mana pun menyentuh tabel module
     * lain, dan tanpa N+1 walau OLT menaungi puluhan ODP. Kosong bila OLT tak punya ODP
     * berpenghuni di hilirnya (atau OLT tak dikenal).
     */
    fun listOnusUnderOlt(oltId: UUID): OltOnuList
}

/** Daftar ONU pelanggan di bawah satu OLT untuk pandangan per-OLT di halaman detail OLT. */
data class OltOnuList(
    val oltId: UUID,
    val onuCount: Int,
    val onus: List<OltOnuRow>,
)

/**
 * Satu ONU terpasang di bawah OLT: perangkat + pemiliknya + di ODP/port mana. [onuStatus]
 * adalah status tercatat (disegarkan monitoring lewat write-back), bukan tarikan hidup —
 * jadi baris ini murni dari network + customer tanpa memanggil monitoring per ONU.
 */
data class OltOnuRow(
    val onuId: UUID,
    val serialNumber: String,
    val customerId: UUID,
    val customerCode: String,
    val customerName: String,
    val odpId: UUID,
    val odpCode: String,
    val portNumber: Int,
    val onuStatus: String,
    val opticalHealth: String,
    /** Redaman baseline saat instalasi; null bila tak dicatat. */
    val installRxPowerDbm: Double?,
    val subscriptionPackage: String?,
    val subscriptionStatus: String?,
)

/**
 * Drill-down utilisasi satu PON port. [capacity]/[used] adalah TOTAL port pelanggan
 * di seluruh ODP di bawahnya — angka perencanaan kapasitas "seberapa penuh PON ini".
 */
data class PonPortInspection(
    val ponPortId: UUID,
    val label: String,
    val oltId: UUID,
    val odcCount: Int,
    val odpCount: Int,
    val capacity: Int,
    val used: Int,
    val utilizationPercent: Int,
    val odcs: List<PonOdcBranch>,
)

/**
 * Satu ODC di bawah PON port dengan rekap utilisasi. [legCapacity] = kaki splitter
 * ODC (kapasitas cabang distribusi); [capacity]/[used] = total port pelanggan di
 * seluruh ODP anak. [odps] memakai [OdpUtilization] yang sama dengan heatmap.
 */
data class PonOdcBranch(
    val odcId: UUID,
    val code: String,
    val name: String,
    /** Aktif dan punya uplink. */
    val energized: Boolean,
    /** Kaki splitter ODC — kapasitas cabang distribusi. */
    val legCapacity: Int,
    val odpCount: Int,
    val capacity: Int,
    val used: Int,
    val utilizationPercent: Int,
    val odps: List<OdpUtilization>,
)

/** Utilisasi port seluruh ODP dalam jangkauan pengguna — bahan heatmap peta. */
data class UtilizationHeatmap(val odps: List<OdpUtilization>)

/** Pemakaian port satu ODP: dasar warna titik heatmap (hijau→kuning→merah). */
data class OdpUtilization(
    val odpId: UUID,
    val code: String,
    val name: String,
    val location: Coordinate,
    val capacity: Int,
    val used: Int,
    /** Port terpakai / kapasitas, dibulatkan ke persen. 0 bila kapasitas 0. */
    val utilizationPercent: Int,
)

data class SiteInspection(
    val siteId: UUID,
    val code: String,
    val name: String,
    val address: String?,
    val location: Coordinate,
    val oltCount: Int,
    val odcCount: Int,
    val odpCount: Int,
    val customerCount: Int,
    val olts: List<SiteOlt>,
)

data class SiteOlt(
    val id: UUID,
    val code: String,
    val name: String,
    val vendor: String,
    /** Aktif dan siap dilayani — dasar penanda sehat/nonaktif di panel. */
    val active: Boolean,
)

data class BlastRadiusView(
    val odcId: UUID,
    val code: String,
    val name: String,
    /** Aktif dan punya uplink. ODC tak-berenergi berarti hilirnya sudah pasti mati. */
    val energized: Boolean,
    val odpCount: Int,
    val customerCount: Int,
    /** Pelanggan yang ONU-nya sedang LOS/OFFLINE — sudah terdampak nyata, bukan hipotetis. */
    val downCount: Int,
    val customers: List<AffectedCustomer>,
)

data class AffectedCustomer(
    val customerId: UUID,
    val code: String,
    val name: String,
    /** Untuk broadcast pemberitahuan (Phase 3). */
    val phone: String?,
    val odpCode: String,
    val onuStatus: String,
    val opticalHealth: String,
)

/**
 * Hasil simulasi memutus sebuah kabel: siapa yang kehilangan layanan bila ruas ini
 * putus, plus geometri kabel yang ikut lenyap agar peta bisa menyorot subpohonnya.
 */
data class CableCutView(
    val cableId: UUID,
    val cableCode: String,
    val cableType: String,
    /** Jenis simpul di ujung hilir yang terputus: ODC/ODP/CUSTOMER. */
    val severedRootKind: String,
    val odcCount: Int,
    val odpCount: Int,
    val customerCount: Int,
    /** Pelanggan yang ONU-nya sudah LOS/OFFLINE — sudah terdampak nyata, bukan hipotetis. */
    val downCount: Int,
    val customers: List<AffectedCustomer>,
    /** Kabel yang lenyap bila ruas ini putus (termasuk ruas itu sendiri), untuk disorot. */
    val severedCables: List<SeveredCable>,
)

data class SeveredCable(
    val id: UUID,
    val code: String,
    val cableType: String,
    val points: List<Coordinate>,
)

/**
 * Sorotan dampak gangguan di peta: kabel yang hilirnya bermasalah [cables] plus
 * perangkat/pelanggan terdampak [nodes] (OLT/ODC/ODP/pelanggan). Kabel diwarnai
 * merah lewat garis overlay; simpul diwarnai dengan mencocokkan id fitur peta —
 * jadi saat OLT mati, perangkatnya ikut merah, bukan cuma kabelnya.
 */
data class ImpactedOverlay(
    val cables: List<ImpactedCable>,
    val nodes: List<ImpactedNode>,
)

/**
 * Satu simpul terdampak. [id] adalah id perangkat/pelanggan yang sama dengan id
 * fitur pada vector tile, sehingga frontend cukup mencocokkan tanpa perlu tahu
 * jenisnya — id UUID unik global lintas layer.
 */
data class ImpactedNode(
    val id: UUID,
    /** WARNING atau CRITICAL — keparahan tertinggi dari alarm yang menimpanya. */
    val severity: String,
)

data class ImpactedCable(
    val id: UUID,
    val code: String,
    val cableType: String,
    /** WARNING atau CRITICAL — keparahan tertinggi di antara ujung yang terdampak. */
    val severity: String,
    val points: List<Coordinate>,
    /** Alarm hidup yang membuat kabel ini merah — jawaban "kenapa" saat diklik. */
    val causes: List<ImpactCause>,
)

/** Satu alasan sebuah kabel tersorot: alarm hidup di hilirnya. */
data class ImpactCause(
    val label: String,
    /** Jenis alarm, mis. ONU_LOS, OLT_UNREACHABLE. */
    val kind: String,
    /** WARNING atau CRITICAL. */
    val severity: String,
)

data class OdpInspection(
    val odpId: UUID,
    val code: String,
    val name: String,
    val location: Coordinate,
    val capacity: Int,
    val usedPorts: Int,
    val availablePortNumbers: List<Int>,
    val utilizationPercent: Int,
    val upstream: UpstreamView,
    val occupants: List<OdpOccupant>,
)

data class UpstreamView(
    val odcCode: String?,
    val odcName: String?,
    val ponPortLabel: String?,
    val oltCode: String?,
    val oltName: String?,
    val siteCode: String?,
    val siteName: String?,
    val complete: Boolean,
    val splitterLossDb: Double,
)

/**
 * Jalur fisik satu pelanggan ke hulu, dari ONT (rumah) sampai BRAS.
 *
 * [estimatedLossDb] adalah perkiraan kasar: rugi splitter (yang dominan) ditambah
 * redaman serat menurut jarak garis lurus. Bukan pengganti pengukuran OTDR, tapi
 * cukup untuk menandai sambungan yang anggarannya sudah mepet sejak di atas kertas.
 *
 * [bras] adalah puncak jalur logis (tempat sesi PPPoE ditutup, di atas OLT) —
 * `null` bila pelanggan belum diprovisi akun PPPoE. [liveRxPowerDbm]/[liveOnuStatus]/
 * [distanceMeters] adalah bacaan optik HIDUP terakhir dari monitoring pada ONU
 * pelanggan (beda dari [installRxPowerDbm] yang baseline saat instalasi); `null`
 * bila ONU belum pernah terbaca.
 */
data class CustomerTrace(
    val customerId: UUID,
    val customerCode: String,
    val customerName: String,
    val location: Coordinate,
    val onuSerialNumber: String?,
    val onuStatus: String?,
    val installRxPowerDbm: Double?,
    val opticalHealth: String?,
    val odpPortNumber: Int?,
    val upstream: UpstreamView?,
    val estimatedLossDb: Double?,
    val bras: BrasHopView?,
    val liveOnuStatus: String?,
    val liveRxPowerDbm: Double?,
    val distanceMeters: Int?,
    val hops: List<TraceHop>,
    /**
     * Perangkat CPE/ONT pelanggan bila ada di ACS — dibawa agar panel peta bisa
     * langsung menawarkan Reboot/Ping tanpa memanggil daftar perangkat lebih dulu.
     * `null` berarti tak ada CPE tertaut (serial ONU tak cocok, atau memang bukan
     * perangkat TR-069).
     */
    val cpeDeviceId: UUID?,
    /** Status online CPE menurut ACS; `null` bila [cpeDeviceId] null. */
    val cpeOnline: Boolean?,
)

/**
 * Hop BRAS pada telusur jalur: identitas jaringan pelanggan (akun PPPoE) beserta
 * keadaan sesi terkininya. Tanpa rahasia apa pun. [online] false berarti BRAS
 * melaporkan akun tak sedang tersambung (atau belum pernah terpantau).
 */
data class BrasHopView(
    /**
     * Id akun jaringan — dibawa supaya panel peta bisa langsung menembak aksi
     * (Reset Login, isolir) tanpa memanggil balik daftar akun pelanggan.
     */
    val accessId: UUID,
    val username: String,
    /** Status akun jaringan: ACTIVE/ISOLATED/TERMINATED. */
    val accessStatus: String,
    val rateProfileName: String?,
    val online: Boolean,
    val framedIp: String?,
    val nasName: String?,
    val nasIp: String?,
    val uptimeSeconds: Long?,
    /** Mulai sesi yang sedang berjalan; `null` bila tak sedang online. */
    val startedAt: Instant?,
    /**
     * Terakhir kali BRAS melaporkan akun ini. Saat [online] false inilah "putus sejak
     * kapan" — pembeda antara baru saja lepas dan sudah lama menghilang.
     */
    val lastSeenAt: Instant?,
)

data class TraceHop(
    val kind: String,
    val code: String,
    val name: String,
    val location: Coordinate?,
    /** Khusus hop BRAS: apakah sesi sedang online. `null` untuk hop non-BRAS. */
    val online: Boolean? = null,
    /** Keterangan inline siap-tampil (mis. "IP 100.64.0.5 · uptime 2j", "Rx −21.4 dBm"). */
    val detail: String? = null,
)

/**
 * Tetangga sejalur seorang pelanggan dalam dua lingkup: satu ODP (paling dekat,
 * berbagi kabel drop & splitter ODP) dan satu PON port (lebih luas, berbagi port
 * OLT). [samePonPort] adalah superset — memang termasuk penghuni [sameOdp], persis
 * seperti di lapangan. Kosong bila pelanggan belum tersambung.
 */
data class SubscriberNeighbors(
    val customerId: UUID,
    val odpCode: String?,
    val ponPortLabel: String?,
    val sameOdp: List<NeighborView>,
    val samePonPort: List<NeighborView>,
)

/** Satu tetangga sejalur: identitas + kondisi terpasang + bacaan hidup ONU-nya. */
data class NeighborView(
    val customerId: UUID,
    val customerCode: String,
    val customerName: String,
    val odpCode: String,
    val portNumber: Int,
    val onuSerialNumber: String,
    /** Status ONU menurut catatan (ONLINE/OFFLINE/LOS/…), sebelum diperkaya monitoring. */
    val onuStatus: String,
    val opticalHealth: String,
    val installRxPowerDbm: Double?,
    /** Bacaan hidup terakhir dari monitoring; `null` bila ONU belum pernah terbaca. */
    val liveStatus: String?,
    val liveRxPowerDbm: Double?,
    val distanceMeters: Int?,
    val downCause: String?,
    /** Baris pelanggan yang sedang ditelusur — untuk disorot di daftar. */
    val self: Boolean,
)

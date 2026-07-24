package com.duluin.ftth.gis.application.port.inbound

import com.duluin.ftth.common.domain.geo.Coordinate
import com.duluin.ftth.customer.OdpOccupant
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
}

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

data class ImpactedOverlay(val cables: List<ImpactedCable>)

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
 * Jalur fisik satu pelanggan ke hulu.
 *
 * [estimatedLossDb] adalah perkiraan kasar: rugi splitter (yang dominan) ditambah
 * redaman serat menurut jarak garis lurus. Bukan pengganti pengukuran OTDR, tapi
 * cukup untuk menandai sambungan yang anggarannya sudah mepet sejak di atas kertas.
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
    val hops: List<TraceHop>,
)

data class TraceHop(
    val kind: String,
    val code: String,
    val name: String,
    val location: Coordinate?,
)

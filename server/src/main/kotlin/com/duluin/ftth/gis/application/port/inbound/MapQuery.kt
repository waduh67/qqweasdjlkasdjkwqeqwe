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
}

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

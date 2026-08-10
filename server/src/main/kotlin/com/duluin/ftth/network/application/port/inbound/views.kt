package com.duluin.ftth.network.application.port.inbound

import com.duluin.ftth.common.domain.geo.Coordinate
import com.duluin.ftth.common.domain.geo.RoutePath
import com.duluin.ftth.network.domain.model.AssetStatus
import com.duluin.ftth.network.domain.model.CableInstallation
import com.duluin.ftth.network.domain.model.CableOwnership
import com.duluin.ftth.network.domain.model.CableType
import com.duluin.ftth.network.domain.model.ClosureKind
import com.duluin.ftth.network.domain.model.ConnectionPointKind
import com.duluin.ftth.network.domain.model.CoreStatus
import com.duluin.ftth.network.domain.model.NetworkNodeKind
import com.duluin.ftth.network.domain.model.OltVendor
import com.duluin.ftth.network.domain.model.SnmpVersion
import com.duluin.ftth.network.domain.model.SpliceMethod
import com.duluin.ftth.network.domain.model.WebProtocol
import java.util.UUID

/**
 * Bentuk baca (read model) yang dikembalikan use case ke lapisan web.
 *
 * Sengaja dipisahkan dari agregat domain: agregat boleh berubah bentuk mengikuti
 * kebutuhan invariant, sedangkan view adalah kontrak yang dilihat klien.
 */

data class SiteView(
    val id: UUID,
    val code: String,
    val name: String,
    val address: String?,
    val location: Coordinate,
    val areaId: UUID?,
    val oltCount: Long,
)

data class OltView(
    val id: UUID,
    val code: String,
    val name: String,
    val siteId: UUID,
    val siteName: String?,
    val vendor: OltVendor,
    val model: String?,
    val managementIp: String?,
    val status: AssetStatus,
    /** Community string TIDAK pernah dikembalikan — hanya penanda ada/tidaknya. */
    val snmpConfigured: Boolean,
    val snmpPort: Int,
    val pollable: Boolean,
    val ponPortCount: Int,
    val location: Coordinate,
    val areaId: UUID?,
    val description: String?,
    val snmpEnabled: Boolean,
    val snmpVersion: SnmpVersion,
    val webEnabled: Boolean,
    val webProtocol: WebProtocol,
    val webPort: Int?,
    val webUsername: String?,
    /** Password Web TIDAK pernah dikembalikan — hanya penanda ada/tidaknya. */
    val webPasswordConfigured: Boolean,
)

data class PonPortView(
    val id: UUID,
    val oltId: UUID,
    val label: String,
    val description: String?,
    val status: AssetStatus,
    val odcCount: Long,
)

data class OdcView(
    val id: UUID,
    val code: String,
    val name: String,
    val address: String?,
    val location: Coordinate,
    val areaId: UUID?,
    val ponPortId: UUID?,
    val ponPortLabel: String?,
    val oltName: String?,
    val splitterRatio: String,
    val capacity: Int,
    val odpCount: Long,
    val status: AssetStatus,
    val energized: Boolean,
)

data class OdpView(
    val id: UUID,
    val code: String,
    val name: String,
    val address: String?,
    val location: Coordinate,
    val areaId: UUID?,
    val odcId: UUID?,
    val odcName: String?,
    val splitterRatio: String,
    val capacity: Int,
    val status: AssetStatus,
)

/**
 * Satu pilihan port KELUARAN pada simpul sumber, untuk picker "colok dari port
 * mana" saat menarik kabel. [ponPortId] terisi untuk OLT (PON port berlabel),
 * [portNumber] untuk kaki splitter ODC / slot ODP. [occupied] menandai port yang
 * sudah dipakai kabel lain sehingga tak boleh dipilih lagi.
 */
data class CablePortOption(
    val ponPortId: UUID?,
    val portNumber: Int?,
    val label: String,
    val occupied: Boolean,
    /** Kode kabel yang menempati port ini, bila [occupied]. */
    val occupiedByCable: String?,
)

data class CableView(
    val id: UUID,
    val code: String,
    val name: String,
    val cableType: CableType,
    val coreCount: Int,
    val route: RoutePath,
    val lengthMeters: Double,
    val fromKind: NetworkNodeKind,
    val fromId: UUID,
    val toKind: NetworkNodeKind,
    val toId: UUID,
    /** FEEDER: PON port OLT sumber; null bila kabel legacy / ujung SITE. */
    val fromPonPortId: UUID?,
    /** Sumber: kaki splitter ODC / slot ODP; null bila legacy. */
    val fromPortNumber: Int?,
    /** Input tujuan; null bila tak dipilih. */
    val toPortNumber: Int?,
    /** Label siap-tampil port keluaran sumber, mis. "PON 1/1/1" / "Kaki 3" / "Slot 5". */
    val fromPortLabel: String?,
    val status: AssetStatus,
    /** Cara pasang; null = belum disurvei (bukan "tak terpasang"). */
    val installation: CableInstallation?,
    val installationLabel: String?,
    val ownership: CableOwnership,
    val ownershipLabel: String,
)

/**
 * Sehelai core siap tampil. Warna dikirim sebagai label + hex sekaligus: hex-nya
 * warna FISIK selubung serat (TIA-598), bukan token tema — klien menggambar
 * chip persis seperti yang dipegang teknisi tanpa menyalin tabel warna sendiri.
 */
data class CableCoreView(
    val id: UUID,
    val tubeNumber: Int,
    val coreNumber: Int,
    /** Posisi core di dalam tube-nya — penentu warna, mis. core 13 = posisi 1. */
    val positionInTube: Int,
    val color: String,
    val colorHex: String,
    val tubeColor: String,
    val tubeColorHex: String,
    val status: CoreStatus,
    val note: String?,
)

/**
 * Satu ujung sambungan, siap tampil.
 *
 * Titik core dilengkapi asal-usulnya (kabel, nomor, warna) karena di lapangan
 * orang tak pernah menyebut core lewat id-nya — yang dipegang teknisi adalah
 * "serat hijau di tube pertama kabel Dist-01". Tanpa itu layar splicing cuma
 * deretan UUID.
 */
data class FiberConnectionPointView(
    val kind: ConnectionPointKind,
    val kindLabel: String,
    /** Uraian siap-pakai, mis. "Core 3 · Hijau · DIST-01" atau "Kaki splitter 4". */
    val label: String,
    val coreId: UUID?,
    val cableId: UUID?,
    val cableCode: String?,
    val coreNumber: Int?,
    /** Warna FISIK selubung serat (TIA-598), bukan token tema. */
    val colorHex: String?,
    val nodeId: UUID?,
    val portNumber: Int?,
)

data class FiberConnectionView(
    val id: UUID,
    val closureKind: ClosureKind,
    val closureId: UUID,
    val a: FiberConnectionPointView,
    val b: FiberConnectionPointView,
    val method: SpliceMethod,
    val methodLabel: String,
    /** Rugi hasil ukur; null = belum diukur, bukan nol. */
    val lossDb: Double?,
    val note: String?,
)

/**
 * Isi sebuah closure: identitasnya plus semua sambungan di dalamnya — persis
 * yang dilihat saat kotaknya dibuka. Identitas ikut dikirim supaya layar
 * splicing tak perlu memanggil endpoint ODC/ODP hanya demi judul.
 */
data class ClosureSpliceView(
    val closureKind: ClosureKind,
    val closureId: UUID,
    val closureCode: String,
    val closureName: String,
    val connections: List<FiberConnectionView>,
)

/**
 * Barisan core sebuah kabel plus hitungan per status — ringkasan "berapa yang
 * masih bisa dijual" yang selalu ditanya duluan, tanpa klien harus menghitung
 * sendiri dari daftarnya.
 */
data class CableCoreListView(
    val cableId: UUID,
    val cableCode: String,
    val cableName: String,
    val coreCount: Int,
    /** Isi satu tube; klien memakainya untuk memecah grid per tube. */
    val coresPerTube: Int,
    val free: Int,
    val used: Int,
    val reserved: Int,
    val damaged: Int,
    val cores: List<CableCoreView>,
)

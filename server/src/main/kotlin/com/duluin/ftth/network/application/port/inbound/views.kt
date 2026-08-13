package com.duluin.ftth.network.application.port.inbound

import com.duluin.ftth.common.domain.geo.Coordinate
import com.duluin.ftth.common.domain.geo.RoutePath
import com.duluin.ftth.network.domain.model.AssetStatus
import com.duluin.ftth.network.domain.model.CableAttachmentRole
import com.duluin.ftth.network.domain.model.CableInstallation
import com.duluin.ftth.network.domain.model.CableOwnership
import com.duluin.ftth.network.domain.model.CableType
import com.duluin.ftth.network.domain.model.ClosureKind
import com.duluin.ftth.network.domain.model.ConnectionPointKind
import com.duluin.ftth.network.domain.model.CoreStatus
import com.duluin.ftth.network.domain.model.FiberHopKind
import com.duluin.ftth.network.domain.model.FiberTraceEnd
import com.duluin.ftth.network.domain.model.MountingType
import com.duluin.ftth.network.domain.model.NetworkNodeKind
import com.duluin.ftth.network.domain.model.OdfPortSide
import com.duluin.ftth.network.domain.model.OdpPortIssue
import com.duluin.ftth.network.domain.model.OltVendor
import com.duluin.ftth.network.domain.model.SnmpVersion
import com.duluin.ftth.network.domain.model.SpliceMethod
import com.duluin.ftth.network.domain.model.WebProtocol
import java.time.Instant
import java.time.LocalDate
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
    /** Ringkasan isi kabinet: "1:8", "1:8 ×2 · 1:16", atau "—" bila tak bersplitter. */
    val splitterRatio: String,
    val splitterCount: Int,
    /** Jumlah kaki keluar seluruh modul — "berapa yang bisa dijual dari kabinet ini". */
    val splitterLegs: Int,
    val capacity: Int,
    val odpCount: Long,
    val status: AssetStatus,
    val energized: Boolean,
    /** Data lapangan; null selama belum pernah diisi. Lihat [MountingType]. */
    val installedOn: LocalDate?,
    val mounting: MountingType?,
    val notes: String?,
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
    /** Ringkasan isi kotak; lihat [OdcView.splitterRatio]. */
    val splitterRatio: String,
    val splitterCount: Int,
    val splitterLegs: Int,
    val capacity: Int,
    val status: AssetStatus,
    /** Lihat [OdcView.installedOn] dkk. */
    val installedOn: LocalDate?,
    val mounting: MountingType?,
    val notes: String?,
)

/**
 * Satu modul splitter siap tampil.
 *
 * [usedLegs] dikirim sebagai daftar nomor, bukan sekadar jumlah: yang ditanya di
 * depan kabinet adalah "kaki mana yang masih kosong", dan jumlah saja tak bisa
 * menjawabnya begitu kaki dilepas di tengah (3 terpakai bisa berarti kaki 1,2,5).
 * [inputConnected] memisahkan modul yang sudah disuapi feeder dari modul yang
 * terpasang tapi belum hidup — bedanya nyata saat mencari kenapa satu blok mati.
 */
data class SplitterView(
    val id: UUID,
    val ownerKind: ClosureKind,
    val ownerId: UUID,
    val ownerCode: String?,
    val code: String,
    val ratio: String,
    val legCount: Int,
    val insertionLossDb: Double,
    val usedLegs: List<Int>,
    val inputConnected: Boolean,
    val note: String?,
)

/**
 * Isi sebuah kabinet: identitasnya plus modul-modulnya. Identitas ikut dikirim
 * supaya layar splitter tak perlu memanggil endpoint ODC/ODP hanya demi judul.
 */
data class ClosureSplitterView(
    val ownerKind: ClosureKind,
    val ownerId: UUID,
    val ownerCode: String,
    val ownerName: String,
    val splitters: List<SplitterView>,
)

/**
 * Tanpa `splitterRatio` — dan itu memang inti perbedaannya dengan ODC/ODP.
 * [spliceCount] adalah isi kotaknya saat ini, dipakai UI untuk menampilkan
 * "12/24 sambungan" tanpa memuat daftarnya.
 */
data class JointBoxView(
    val id: UUID,
    val code: String,
    val name: String,
    val address: String?,
    val location: Coordinate,
    val areaId: UUID?,
    val trayCount: Int,
    val capacity: Int,
    val spliceCount: Long,
    val status: AssetStatus,
    /** Lihat [OdcView.installedOn] dkk. */
    val installedOn: LocalDate?,
    val mounting: MountingType?,
    val notes: String?,
)

/**
 * Rak terminasi di POP. Dua angka pemakaian, dan keduanya perlu:
 *
 *  - [usedPortCount] menjawab "masih ada slot kosong?" — satu port dihitung
 *    sekali walau kedua sisinya sudah tersambung, sebab yang habis di rak adalah
 *    adapternya, bukan sisinya.
 *  - [spliceCount] menjawab "berapa banyak sambungan di dalamnya" — sisi
 *    belakang dan depan dihitung sendiri-sendiri karena keduanya memang kerja
 *    terpisah.
 */
data class OdfView(
    val id: UUID,
    val code: String,
    val name: String,
    val siteId: UUID,
    val siteName: String?,
    val location: Coordinate,
    val areaId: UUID?,
    val portCount: Int,
    val usedPortCount: Long,
    val spliceCount: Long,
    val status: AssetStatus,
    /** Lihat [OdfUplinkView]. Kosong = belum ada patchcord yang tercatat. */
    val olts: List<OdfUplinkView>,
)

/**
 * OLT yang patchcord-nya benar-benar mendarat di sebuah rak, beserta berapa port
 * rak yang dipakainya.
 *
 * Turunan — tak ada yang pernah mengetiknya, dan itu disengaja. "OLT terkait"
 * sebagai isian tunggal mulai berbohong tepat pada hari OLT kedua masuk POP, dan
 * bohongnya tak kelihatan karena kolomnya tetap terisi rapi. Satu rak lazim
 * melayani beberapa OLT sekaligus, dan satu OLT memakai banyak port di rak yang
 * sama; yang benar cuma bisa dibaca dari patchcord yang sudah tercatat.
 */
data class OdfUplinkView(
    val oltId: UUID,
    val oltCode: String,
    val oltName: String,
    /** Berapa port rak ini yang dipakai OLT tersebut. */
    val portCount: Int,
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
    /**
     * Slot ODP asal sebuah drop; untuk sumber ODC (kaki splitter) kolom ini USANG
     * — lihat [com.duluin.ftth.network.domain.model.NetworkEndpoint]. Tetap
     * dikirim supaya catatan lama tetap terbaca di layar.
     */
    val fromPortNumber: Int?,
    /** Input tujuan; null bila tak dipilih. */
    val toPortNumber: Int?,
    /** Label siap-tampil port keluaran sumber, mis. "PON 1/1/1" / "Kaki 3" / "Slot 5". */
    val fromPortLabel: String?,
    /**
     * Seluruh simpul yang disinggahi kabel ini, urut dari pangkal ke ujung —
     * termasuk kedua ujungnya sendiri.
     *
     * [fromKind]/[toKind] dan kawan-kawan tetap dikirim dan tetap berarti sama;
     * keduanya kini cuma ringkasan dari elemen pertama & terakhir daftar ini.
     * Yang tak bisa dikatakan sepasang ujung adalah belasan kotak di tengah
     * bentang yang selubungnya dibuka satu per satu — dan itulah yang ada di sini.
     */
    val attachments: List<CableAttachmentView>,
    val status: AssetStatus,
    /** Cara pasang; null = belum disurvei (bukan "tak terpasang"). */
    val installation: CableInstallation?,
    val installationLabel: String?,
    val ownership: CableOwnership,
    val ownershipLabel: String,
)

/**
 * Satu singgahan kabel siap tampil.
 *
 * [distanceMeters] sengaja disebut TAMPILAN, bukan penentu: ia menjawab "di meter
 * ke berapa kotak ini dari pangkal" supaya teknisi tahu urutan berjalannya, dan
 * dihitung dari letak kotak di peta. Yang menentukan kabel ini boleh disambung di
 * sana tetap [role] — perbuatan orang atas selubungnya.
 */
data class CableAttachmentView(
    val id: UUID,
    /** Urutan menyusuri kabel dari pangkal; 0 = pangkal, terbesar = ujung. */
    val sequence: Int,
    val nodeKind: NetworkNodeKind,
    val nodeId: UUID,
    /** Kode & nama kotaknya; null bila simpulnya bukan kotak (POP/OLT/rumah) atau sudah terhapus. */
    val nodeCode: String?,
    val nodeName: String?,
    val role: CableAttachmentRole,
    val roleLabel: String,
    /** Selubung sudah terbuka di sini, jadi core-nya bisa disambung. */
    val spliceable: Boolean,
    /** Jarak dari pangkal kabel menyusuri rute, meter; null bila letak simpulnya tak diketahui. */
    val distanceMeters: Double?,
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
    /** Sisi port ODF (belakang/depan); null untuk titik jenis lain. */
    val portSide: OdfPortSide?,
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
    /**
     * Jejak pengerjaan: tiket mana, siapa, kapan. Semua boleh null untuk baris
     * lama yang lahir sebelum jejak ini ada — dan itu dibiarkan apa adanya, sebab
     * mengarang pelaku demi kolom yang penuh jauh lebih berbahaya daripada
     * kolom kosong yang jujur.
     */
    val workOrderId: UUID?,
    val workOrderCode: String?,
    val splicedById: UUID?,
    val splicedByName: String?,
    val splicedAt: Instant,
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
 * Hasil pindah serat: keadaan kedua helai SESUDAHNYA plus sambungan yang ikut
 * berpindah.
 *
 * Dikembalikan selengkap ini supaya layar bisa langsung memperlihatkan apa yang
 * berubah tanpa memuat ulang seisi meja kerja — dan supaya orang yang menekan
 * tombolnya melihat hitam di atas putih berapa sambungan yang tersentuh, bukan
 * cuma "berhasil".
 */
data class CoreMoveView(
    val cableId: UUID,
    val cableCode: String,
    val fromCore: CableCoreView,
    val toCore: CableCoreView,
    val movedConnections: List<FiberConnectionView>,
)

/**
 * Sehelai core sebagaimana terlihat DARI DALAM sebuah kotak.
 *
 * [connectionId] dan [connectedElsewhere] menjawab dua pertanyaan berbeda yang
 * mudah tertukar. Core yang sudah disambung DI SINI tak boleh disambung dua kali
 * di kotak yang sama. Core yang tersambung di kotak LAIN justru masih boleh
 * dipakai di sini — sehelai serat punya dua ujung, dan itu memang bentuk
 * normalnya: ujung ODC dilas di ODC, ujung ODP dilas di ODP.
 */
data class SpliceCoreView(
    val core: CableCoreView,
    val connectionId: UUID?,
    val connectedElsewhere: Boolean,
)

/**
 * Satu kabel yang tercatat menyinggahi kotak yang sedang dibuka.
 *
 * [role] adalah keterangan terpenting di baris ini, sebab ia menentukan apa yang
 * boleh dikerjakan: END berarti selubungnya habis di sini dan seluruh core
 * terbuka; TAPPED berarti dikupas di tengah bentang, sebagian core diambil,
 * sisanya jalan terus; PASSING berarti selubungnya UTUH — kabelnya ada di dalam
 * kotak, terlihat, tapi tak satu pun core-nya boleh disambung.
 *
 * [terminatesHere] dipertahankan sebagai turunan `role == END` supaya layar yang
 * cuma butuh "berujung di sini atau tidak" tak perlu ikut mengenal peran.
 * [tapDistanceMeters] letak kotak ini diukur dari pangkal kabel — dihitung dari
 * geometri untuk mengurutkan tampilan, bukan untuk menentukan boleh-tidaknya
 * menyambung.
 */
data class SpliceCableView(
    val cableId: UUID,
    val code: String,
    val name: String,
    val cableType: CableType,
    val coreCount: Int,
    val lengthMeters: Double,
    val role: CableAttachmentRole,
    val roleLabel: String,
    /** Selubungnya terbuka di sini — hanya kabel begini yang core-nya bisa dilas. */
    val spliceable: Boolean,
    val terminatesHere: Boolean,
    val tapDistanceMeters: Double,
    val cores: List<SpliceCoreView>,
)

/**
 * Titik NON-core yang tersedia di kotak ini: kaki & input splitter, sisi port
 * ODF, PON port OLT. Daftarnya menyesuaikan jenis simpul — joint box tak
 * memunculkan satu pun, sebab di dalamnya serat memang cuma bertemu serat.
 *
 * [group] adalah judul kelompok siap-tampil ("SPL-1 · 1:8", "Rak ODF-01",
 * "OLT-POP-A") supaya klien tak perlu merangkai label sendiri dari beberapa
 * endpoint.
 */
data class SplicePointView(
    val kind: ConnectionPointKind,
    val nodeId: UUID,
    val portNumber: Int?,
    val portSide: OdfPortSide?,
    val label: String,
    val group: String,
    /** Sambungan yang memakainya, di kotak mana pun; null = titik masih bebas. */
    val connectionId: UUID?,
    /**
     * Jalur di ujung titik ini menurut SERAT, siap tampil: "DROP-ODP-XXX-01-P1 ·
     * Budi Santoso", "menyuapi SPL-2", "DIST-… → ODP-XXX-02". Null = belum
     * tersambung, atau jenis titik yang memang tak ditelusuri.
     *
     * Ada supaya "kaki 3" berhenti jadi nomor tanpa arti. Teknisi yang membuka
     * kotak mencari kaki milik pelanggan yang mengeluh, dan tanpa keterangan ini
     * satu-satunya cara adalah mencabut kaki satu per satu sampai ada yang mati.
     */
    val serves: String? = null,
)

/**
 * Seisi meja kerja Splicing & Patching untuk SATU kotak: kabel yang lewat beserta
 * core-nya, titik-titik simpul yang tersedia, dan sambungan yang sudah ada.
 *
 * Dikirim sebagai satu bongkah karena begitulah pekerjaannya: kotak dibuka
 * sekali, dan semua yang ada di dalamnya harus terlihat berbarengan. Merakitnya
 * dari lima endpoint terpisah di sisi klien berarti layar yang berkedip
 * sepotong-sepotong di atas koneksi lapangan yang lambat.
 */
data class SpliceWorkbenchView(
    val closureKind: ClosureKind,
    val closureId: UUID,
    val closureCode: String,
    val closureName: String,
    /** Batas jumlah sambungan yang muat; null = tak dibatasi (ODC/ODP). */
    val spliceCapacity: Int?,
    val spliceCount: Int,
    val cables: List<SpliceCableView>,
    val points: List<SplicePointView>,
    val connections: List<FiberConnectionView>,
)

/**
 * Satu lubang di faceplate ODP, dengan KEDUA catatannya bersanding: pemasangan
 * ONU yang dibukukan orang layanan, dan serat yang dilas teknisi.
 *
 * Sengaja satu baris memuat dua sumber, bukan dua daftar terpisah. Yang ingin
 * diketahui di depan kotak bukan "siapa saja pelanggan di sini" atau "kaki mana
 * saja yang terpakai" sendiri-sendiri — melainkan apakah keduanya bercerita hal
 * yang sama tentang lubang yang sedang ditunjuk jari.
 */
data class OdpPortRowView(
    /** null = ONU tercatat di kotak ini tanpa nomor port; barisnya di paling bawah. */
    val portNumber: Int?,
    /** Kaki yang secara fisik dipigtail ke lubang ini, mis. "SPL-1 kaki 3". */
    val legLabel: String?,
    val legConnected: Boolean,
    /** Jalur di ujung kaki menurut SERAT; lihat [SplicePointView.serves]. */
    val servedBy: String?,
    val customerId: UUID?,
    val customerName: String?,
    val onuSerialNumber: String?,
    val onuStatus: String?,
    val opticalHealth: String?,
    val rxPowerDbm: Double?,
    val issue: OdpPortIssue?,
    /**
     * Sebutan pendek [issue] untuk lencana, mis. "Catatan & serat beda orang".
     * Ikut dikirim bersama [issueDetail] supaya kata-kata satu keadaan tak
     * terpecah dua tempat — layar cukup menampilkan, tak ikut menamai.
     */
    val issueLabel: String?,
    /** Kalimat penjelas [issue] siap tampil; null bila portnya memang beres. */
    val issueDetail: String?,
)

/**
 * Papan port sebuah ODP: seluruh lubangnya, penghuninya, dan selisih antara
 * catatan dan serat.
 *
 * Dikirim sebagai satu bongkah dengan alasan yang sama seperti meja sambung —
 * kotak dibuka sekali, dan yang berdiri di depannya butuh semuanya berbarengan.
 */
data class OdpPortBoardView(
    val odpId: UUID,
    val odpCode: String,
    val capacity: Int,
    /** Modul yang kakinya dipetakan ke port, urut kode; kosong = kotak tanpa splitter. */
    val splitterCodes: List<String>,
    val ports: List<OdpPortRowView>,
    val occupiedCount: Int,
    /** Berapa baris yang catatannya berselisih dengan seratnya. */
    val issueCount: Int,
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

/**
 * Satu langkah dalam penelusuran jalur — apa yang dilewati cahaya dan berapa
 * ongkosnya di situ.
 *
 * [lossDb] hop ini sendiri, [cumulativeLossDb] jumlah dari OLT sampai hop ini.
 * Dua-duanya dibawa supaya layar bisa menunjuk hop mana yang menghabiskan
 * anggaran, bukan cuma memberi satu angka akhir yang tak bisa ditindaklanjuti.
 */
data class FiberHopView(
    val kind: FiberHopKind,
    val kindLabel: String,
    val label: String,
    val detail: String,
    val lossDb: Double,
    val cumulativeLossDb: Double,
    /** Rugi hop ini hasil UKUR; false = angka tipikal komponen. */
    val measured: Boolean,
    /** Kotak tempat hop ini berada — untuk mengarahkan orang ke lokasinya. */
    val closureKind: ClosureKind?,
    val closureId: UUID?,
    val closureCode: String?,
    /** Kabel yang seratnya dilewati; hanya untuk hop serat. */
    val cableId: UUID?,
    /** Splitter/ODF/PON port yang dilewati; hanya untuk hop perangkat. */
    val nodeId: UUID?,
)

/**
 * Jalur satu helai cahaya dari OLT sampai titik yang ditanyakan, lengkap dengan
 * anggaran redamannya.
 *
 * Hop diurut dari OLT ke titik awal telusur — arah CAHAYA, bukan arah orang
 * berjalan menyusurinya. Itu yang membuat "rugi kumulatif" punya arti: tiap
 * angka menjawab "sampai di sini, berapa yang sudah habis".
 */
data class FiberPathView(
    val startLabel: String,
    val end: FiberTraceEnd,
    val endLabel: String,
    val hops: List<FiberHopView>,
    val totalLossDb: Double,
    /** Anggaran daya kelas B+ yang dipakai sebagai pembanding. */
    val budgetDb: Double,
    /** Sisa anggaran; negatif berarti jalur ini secara hitungan sudah gelap. */
    val marginDb: Double,
    val fiberMeters: Double,
    val splitterCount: Int,
    val spliceCount: Int,
    /** Hop yang rugi-nya masih angka tipikal, belum diukur. */
    val estimatedHops: Int,
    /** Kalimat siap-baca; kosong berarti jalurnya sehat dan utuh. */
    val warnings: List<String>,
)

/**
 * Muatan satu port PON: apa saja yang benar-benar tergantung di bawahnya, dan
 * seberapa dekat ia ke plafon 64 ONU milik GPON.
 *
 * Angka [onuCount] datang dari GRAF SAMBUNGAN, bukan dari kolom "ODC ini milik
 * PON port itu" — sebab kolom itu diisi tangan saat kabinet dibuat dan tak
 * pernah diperbarui ketika seratnya dipindah di lapangan. [fromSplicing]
 * mengaku terus terang mana dari keduanya yang dipakai, karena hitungan muatan
 * yang sumbernya disembunyikan lebih berbahaya daripada tak ada hitungan sama
 * sekali.
 */
data class PonPortLoadView(
    val ponPortId: UUID,
    val label: String,
    val oltId: UUID,
    val oltCode: String?,
    val oltName: String?,
    /** Kotak di hilir port ini, urut dari yang terdekat. */
    val closures: List<PonClosureLoadView>,
    /** Total kaki splitter di seluruh kotak itu — plafon fisik yang bisa dijual. */
    val splitterLegs: Int,
    /** Kaki yang sudah tersambung ke sesuatu. */
    val usedLegs: Int,
    /** ONU pelanggan yang menggantung di ODP-ODP di bawah port ini. */
    val onuCount: Int,
    /** Plafon keras GPON, dibawa supaya klien tak menuliskan 64 sendiri. */
    val onuLimit: Int,
    val loadPercent: Int,
    /** true = dirangkai dari catatan splicing; false = dari tautan ODC→PON lama. */
    val fromSplicing: Boolean,
    /** Kalimat siap-baca; kosong berarti port ini masih lapang dan datanya utuh. */
    val warnings: List<String>,
)

/** Satu kotak di hilir port PON beserta sumbangannya pada muatan port itu. */
data class PonClosureLoadView(
    val closureKind: ClosureKind,
    val closureId: UUID,
    val code: String,
    val name: String,
    /** Berapa ruas serat dari port PON; 1 = kotak pertama sesudah rak ODF. */
    val depth: Int,
    val splitterLegs: Int,
    val usedLegs: Int,
    val onuCount: Int,
)

/**
 * Yang kehilangan cahaya bila sebuah kabel putus, dirangkai dari catatan splicing.
 *
 * Dibedakan dari jawaban lama (graf kabel + tautan ODP→ODC) karena keduanya
 * menjawab pertanyaan yang berbeda: yang lama menjawab "kabel mana yang
 * tergantung di bawah kabel ini", yang ini menjawab "serat siapa saja yang lewat
 * di dalam selubung yang digorok". Untuk kabel yang dikupas di banyak kotak,
 * cuma yang kedua yang benar.
 */
data class FiberCutView(
    val cableId: UUID,
    val cableCode: String,
    /** Core kabel yang benar-benar tersambung — bukan jumlah core yang tercetak di selubungnya. */
    val splicedCores: Int,
    /** Core yang sisi hulunya bisa dipastikan, jadi hilirnya sah disebut terdampak. */
    val tracedCores: Int,
    /** Kotak yang gelap bila kabel ini putus, terdekat dulu. */
    val closures: List<FiberCutClosureView>,
    val warnings: List<String>,
)

/** Satu kotak yang kehilangan uplink karena kabel putus. */
data class FiberCutClosureView(
    val closureKind: ClosureKind,
    val closureId: UUID,
    val code: String,
    val name: String,
    /** Berapa ruas serat dari titik putus; 0 = kotak tempat core ini berakhir. */
    val depth: Int,
)

/**
 * Jawaban survey untuk satu titik alamat: apa yang tersedia di sekitarnya.
 *
 * [verdict] sengaja ada di paling depan dan berupa kalimat, bukan kode: yang
 * membaca layar ini sering sales yang sedang berdiri di depan calon pelanggan,
 * dan yang ia butuhkan satu kalimat yang bisa langsung diucapkan.
 */
data class SurveyCapacityView(
    val location: Coordinate,
    val radiusMeters: Double,
    val verdict: String,
    /** Bisa dipasang hari ini: ada kotak siap pakai dalam radius. */
    val serviceable: Boolean,
    val odps: List<SurveyOdpView>,
    val cables: List<SurveyCableView>,
    val warnings: List<String>,
)

/**
 * Sebuah kotak beserta sisa tempatnya.
 *
 * Dua angka yang tampak sama tapi berbeda di lapangan sengaja dipisah:
 * [freePorts] adalah lubang di panel depan, [freeLegs] adalah kaki splitter yang
 * belum dilas ke apa pun. Panel berlubang delapan dengan splitter 1:8 yang
 * kakinya sudah habis TIDAK bisa dijual — dan justru begitulah cara sebuah kotak
 * "kosong" menjatuhkan jadwal pemasangan di hari-H.
 */
data class SurveyOdpView(
    val odpId: UUID,
    val code: String,
    val name: String,
    val address: String?,
    val location: Coordinate,
    /** Garis lurus dari titik survey; kabel drop nyatanya selalu lebih panjang. */
    val distanceMeters: Double,
    val capacity: Int,
    val usedPorts: Int,
    val freePorts: Int,
    val splitterLegs: Int,
    val freeLegs: Int,
    /** Port kosong DAN kaki splitter kosong — baru boleh dijanjikan ke pelanggan. */
    val ready: Boolean,
    /** Kenapa belum siap, bila belum. */
    val note: String? = null,
)

/**
 * Selubung yang lewat di dekat titik survey, beserta core yang masih menganggur.
 *
 * Inilah jalan keluar saat semua kotak penuh: kabel tak harus ditarik ulang dari
 * kabinet — cukup dikupas di tengah bentang, satu core diambil, satu kotak baru
 * berdiri di depan gang. [tapDistanceMeters] adalah letak kupasan itu diukur dari
 * ujung awal kabel, angka yang dipakai teknisi mencari titiknya di lapangan.
 */
data class SurveyCableView(
    val cableId: UUID,
    val code: String,
    val name: String,
    val cableType: CableType,
    /** Jarak titik survey ke jalur kabel — tegak lurus, bukan ke ujungnya. */
    val distanceMeters: Double,
    val tapDistanceMeters: Double,
    val coreCount: Int,
    val freeCores: Int,
    /** Nomor core yang menganggur, beberapa yang pertama saja — sisanya tinggal dibuka di layar core. */
    val freeCoreNumbers: List<Int>,
)

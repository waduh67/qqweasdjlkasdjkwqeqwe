package com.duluin.ftth.network.application.port.inbound

import com.duluin.ftth.common.domain.Page
import com.duluin.ftth.common.domain.PageRequest
import com.duluin.ftth.common.domain.geo.Coordinate
import com.duluin.ftth.network.domain.model.AssetStatus
import com.duluin.ftth.network.domain.model.CableAttachmentRole
import com.duluin.ftth.network.domain.model.CableInstallation
import com.duluin.ftth.network.domain.model.CableOwnership
import com.duluin.ftth.network.domain.model.CableType
import com.duluin.ftth.network.domain.model.NetworkNodeKind
import java.util.UUID

interface ManageCableUseCase {

    fun search(query: String, cableType: CableType?, pageRequest: PageRequest): Page<CableView>

    fun get(id: UUID): CableView

    /**
     * Port keluaran yang tersedia pada sebuah simpul sumber — bahan picker "colok
     * dari port mana". OLT → PON port-nya; ODC/ODP → kaki/slot 1..kapasitas; port
     * yang sudah dipakai kabel lain ditandai occupied. SITE/CUSTOMER → kosong.
     */
    fun sourcePorts(kind: NetworkNodeKind, id: UUID): List<CablePortOption>

    fun create(command: SaveCableCommand): CableView

    fun update(id: UUID, command: SaveCableCommand): CableView

    /**
     * "Selubung kabel ini saya buka di kotak ini" — dicatat dari meja sambung,
     * di saat perbuatannya terjadi.
     *
     * Terpisah dari [update] karena bentuk pekerjaannya memang lain. [update]
     * adalah formulir kabel: sembilan bidang, dibuka orang yang sedang menggambar
     * jalur. Ini satu tindakan, dilakukan orang yang sedang berdiri di depan
     * kotak terbuka — dan kalau ia harus menyunting seluruh kabel dulu, catatan
     * itu tak akan pernah dibuat, lalu tinggallah tebak-tebakan jarak yang
     * selama ini bikin salah potong.
     *
     * Idempoten: kotak yang sudah tercatat cukup diperbarui perannya.
     */
    fun attach(id: UUID, command: AttachCableCommand): CableView

    /**
     * Kebalikan [attach]: "ternyata kabel ini tak pernah dibuka di kotak itu".
     *
     * Idempoten — mencabut yang memang tak tercatat bukan kesalahan, cuma tak
     * mengubah apa-apa. Ditolak bila masih ada serat kabel ini yang tersambung
     * di dalam kotaknya: sambungan yang menggantung pada selubung yang mengaku
     * utuh persis jenis kebohongan yang mau dihabisi skema singgahan.
     */
    fun detach(id: UUID, nodeId: UUID): CableView

    /**
     * "Pelanggannya cabut" dalam satu langkah: sambungan drop ini dilepas, core-nya
     * kembali bebas, dan kabelnya boleh ditandai ditinggal.
     *
     * Tanpa ini, mencabut satu pelanggan berarti membuka meja sambung, mencari
     * baris yang benar — di ODP dan di rumah — melepasnya satu-satu, lalu menyunting
     * status kabelnya di formulir lain. Yang biasanya terjadi: kaki splitternya tak
     * pernah dibebaskan, dan bulan depan kapasitas ODP terlihat penuh padahal
     * seperempatnya milik orang yang sudah pindah dua tahun lalu.
     *
     * Hanya untuk kabel DROP. Melepas seluruh sambungan sebuah feeder/distribusi
     * dalam sekali klik akan memadamkan satu kampung; yang begitu memang harus
     * dikerjakan per core di meja sambung, dengan tangan yang ragu-ragu.
     */
    fun releaseDrop(id: UUID, command: ReleaseDropCommand): DropReleaseView

    fun delete(id: UUID)
}

data class ReleaseDropCommand(
    /**
     * Tandai kabelnya ditinggal. Bukan otomatis: drop yang baru saja dilepas
     * sering langsung dipakai lagi oleh penghuni berikutnya di rumah yang sama,
     * dan menandainya ditinggal cuma menambah pekerjaan menghidupkannya kembali.
     */
    val abandon: Boolean = false,
    val note: String? = null,
)

/** Hasil pencabutan sebuah drop — angkanya disebut supaya bisa diperiksa. */
data class DropReleaseView(
    val cableId: UUID,
    val cableCode: String,
    val removedConnections: Int,
    val freedCores: Int,
    val status: AssetStatus,
    /** Satu kalimat yang siap ditempel di toast, menyebut apa yang benar-benar terjadi. */
    val message: String,
)

data class SaveCableCommand(
    /**
     * Kode di label selubung. Kosong saat create = backend merakitnya dari kode kedua ujung
     * (mis. `DIST-ODC-JKT-001-JB-001`), lengkap dengan akhiran angka bila sudah ada yang
     * memakainya. Kosong saat update = kode yang sekarang dipertahankan, bukan dihapus.
     */
    val code: String?,
    val name: String,
    val cableType: CableType,
    val coreCount: Int,
    val route: List<Coordinate>,
    val fromKind: NetworkNodeKind,
    val fromId: UUID,
    val toKind: NetworkNodeKind,
    val toId: UUID,
    /** FEEDER: PON port OLT sumber. Null = tanpa port (kabel legacy / ujung SITE). */
    val fromPonPortId: UUID? = null,
    /**
     * Slot ODP asal drop. Sebagai "kaki splitter ODC" ia USANG — masih diterima
     * demi klien lama, tapi meja sambung yang jadi patokan; lihat
     * [com.duluin.ftth.network.domain.model.NetworkEndpoint].
     */
    val fromPortNumber: Int? = null,
    /** Input tujuan (opsional, umumnya tunggal). */
    val toPortNumber: Int? = null,
    /**
     * Kotak yang disinggahi di TENGAH bentang, urut dari pangkal ke ujung.
     *
     * `null` = jangan disentuh, dan itu bukan sekadar kehati-hatian: formulir
     * kabel di peta boleh saja cuma menanyakan kedua ujung, dan bila daftar
     * kosong dari sana dianggap kehendak, sekali orang merapikan nama kabel
     * seluruh catatan "dikupas di ODP-3 sampai ODP-11" ikut lenyap tanpa ada
     * yang memintanya. Daftar kosong yang dikirim SADAR tetap berarti "tak ada
     * singgahan".
     */
    val waypoints: List<CableWaypointCommand>? = null,
    val status: AssetStatus,
    /** Null = belum disurvei; disimpan apa adanya, tidak ditebak jadi AERIAL. */
    val installation: CableInstallation? = null,
    val ownership: CableOwnership = CableOwnership.OWNED,
)

/**
 * Satu singgahan di tengah bentang, sebagaimana dikirim klien.
 *
 * Tanpa nomor urut: urutan sepanjang kabel bukan sesuatu yang diketik orang,
 * melainkan akibat dari letak kotaknya di peta — server yang menyusunnya. Tanpa
 * port pula, sebab yang bertemu di tengah bentang adalah core dengan core.
 */
data class CableWaypointCommand(
    val nodeKind: NetworkNodeKind,
    val nodeId: UUID,
    /**
     * Bawaannya "dikupas": kotak yang sengaja didaftarkan orang hampir selalu
     * kotak yang memang dibuka. Yang cuma numpang lewat ditandai sadar.
     */
    val role: CableAttachmentRole = CableAttachmentRole.TAPPED,
)

/** Satu singgahan yang ditambahkan/diperbarui dari meja sambung — lihat [ManageCableUseCase.attach]. */
data class AttachCableCommand(
    val nodeKind: NetworkNodeKind,
    val nodeId: UUID,
    val role: CableAttachmentRole = CableAttachmentRole.TAPPED,
)

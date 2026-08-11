package com.duluin.ftth.network.application.port.inbound

import com.duluin.ftth.common.domain.Page
import com.duluin.ftth.common.domain.PageRequest
import com.duluin.ftth.common.domain.geo.Coordinate
import com.duluin.ftth.network.domain.model.AssetStatus
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
    val status: AssetStatus,
    /** Null = belum disurvei; disimpan apa adanya, tidak ditebak jadi AERIAL. */
    val installation: CableInstallation? = null,
    val ownership: CableOwnership = CableOwnership.OWNED,
)

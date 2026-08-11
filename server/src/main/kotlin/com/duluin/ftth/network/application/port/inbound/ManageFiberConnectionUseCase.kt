package com.duluin.ftth.network.application.port.inbound

import com.duluin.ftth.network.domain.model.ClosureKind
import com.duluin.ftth.network.domain.model.ConnectionPointKind
import com.duluin.ftth.network.domain.model.OdfPortSide
import com.duluin.ftth.network.domain.model.SpliceMethod
import java.util.UUID

/**
 * Sambung & putus serat di dalam sebuah closure.
 *
 * Sengaja sedikit kata kerja — lihat isi closure, sambung, putus — karena itulah
 * yang benar-benar dilakukan di lapangan. Tak ada "pindahkan sambungan ke titik
 * lain": menggeser ujung sambungan berarti membuka pelindung, memotong, lalu
 * menyambung lagi, dan mencatatnya sebagai satu operasi diam-diam menghapus jejak
 * bahwa jalur lama pernah ada.
 *
 * [moveCore] adalah pengecualian yang sengaja sempit dan berlawanan alasannya:
 * di situ justru pekerjaan yang sama dipertahankan utuh — kotak, kaki splitter,
 * tiket, pelaksana — dan yang diganti cuma sehelai serat yang putus dengan helai
 * cadangan di selubung yang sama.
 *
 * Bidang [UpdateFiberConnectionCommand] terpisah karena hasil ukur redaman
 * sering baru masuk belakangan (OPM baru dibawa besoknya) — itu memperbarui
 * catatan, bukan mengubah apa tersambung ke apa.
 */
interface ManageFiberConnectionUseCase {

    fun list(closureKind: ClosureKind, closureId: UUID): ClosureSpliceView

    /**
     * Pekerjaan serat yang dibukukan ke sebuah work order, dikelompokkan per
     * kotak yang dibuka.
     *
     * Dikelompokkan begitu karena begitulah kerjanya berlangsung: teknisi
     * mendatangi kotak, membukanya, mengerjakan beberapa sambungan sekaligus,
     * lalu pindah. Daftar datar berisi dua puluh baris sambungan tak menceritakan
     * bahwa yang didatangi cuma dua kotak.
     */
    fun byWorkOrder(workOrderId: UUID): List<ClosureSpliceView>

    /**
     * [list] plus seluruh bahan untuk MENYAMBUNG: kabel yang lewat kotak ini
     * beserta core-nya, dan titik simpul yang tersedia di dalamnya. Terpisah dari
     * [list] karena yang cuma ingin melihat isi kotak (mis. panel peta) tak perlu
     * membayar penelusuran geometri kabel di sekitarnya.
     */
    fun workbench(closureKind: ClosureKind, closureId: UUID): SpliceWorkbenchView

    fun connect(command: ConnectFiberCommand): FiberConnectionView

    /**
     * Menyambung sekaligus — bahan tombol "sambung 1:1 otomatis", yang di kotak
     * 24 core berarti dua puluh empat sambungan sekali tekan.
     *
     * Semua atau tak sama sekali: kalau pasangan ke-17 ditolak (core rusak, kaki
     * sudah dipakai), tak ada satu pun yang tersimpan. Setengah tersambung lebih
     * buruk daripada gagal — teknisi mengira pekerjaannya batal lalu mengulanginya,
     * dan yang terlanjur masuk jadi sambungan hantu.
     */
    fun connectAll(commands: List<ConnectFiberCommand>): List<FiberConnectionView>

    fun update(id: UUID, command: UpdateFiberConnectionCommand): FiberConnectionView

    /**
     * Memindahkan SELURUH pekerjaan sehelai serat ke helai cadangan di kabel yang
     * sama — satu langkah untuk apa yang di lapangan memang satu kunjungan.
     *
     * Tanpa ini, teknisi harus mencari sendiri setiap kotak yang disinggahi serat
     * itu, melepas sambungannya satu-satu, lalu membuatnya ulang dengan core baru:
     * pekerjaan yang mudah tertinggal separuh (ujung ODC dipindah, ujung ODP lupa)
     * dan yang menghapus riwayat siapa memasangnya kapan. Di sini kedua ujung
     * berpindah bersama, dalam satu transaksi, dengan barisnya dipertahankan.
     *
     * Serat lama ditandai RUSAK, bukan dihapus — ia masih ada secara fisik di
     * dalam selubung, dan orang berikutnya yang membuka kotak itu harus tahu
     * jangan memakainya lagi.
     */
    fun moveCore(command: MoveCoreCommand): CoreMoveView

    fun disconnect(id: UUID)

    /**
     * Memutus semua sambungan yang menyentuh core kabel ini, lalu mengembalikan
     * berapa baris yang benar-benar lepas.
     *
     * Dipanggil sebelum kabel dihapus: core ikut lenyap bersama kabelnya, dan
     * sambungan yang menggantung ke serat yang tak ada lagi adalah kebohongan
     * yang paling mahal — ia membuat telusur jalur menunjuk ke jalur yang sudah
     * digulung.
     *
     * [cableSurvives] dipakai saat kabelnya TETAP ADA dan yang dilepas cuma
     * isinya — mis. drop bekas pelanggan yang cabut. Di situ core kabel ini
     * sendiri ikut dikembalikan ke BEBAS, sebab helai yang tak lagi tersambung
     * ke mana-mana memang siap dipakai pelanggan berikutnya; saat kabelnya
     * dihapus, core-nya lenyap sehingga merapikan statusnya cuma pekerjaan sia-sia.
     */
    fun disconnectAllOfCable(cableId: UUID, cableSurvives: Boolean = false): Int
}

/** Satu ujung yang mau disambung; bentuknya diperiksa domain (lihat ConnectionPoint). */
data class ConnectionPointCommand(
    val kind: ConnectionPointKind,
    val coreId: UUID? = null,
    val nodeId: UUID? = null,
    val portNumber: Int? = null,
    /** Sisi port ODF (belakang/depan); wajib untuk ODF_PORT, terlarang untuk lainnya. */
    val portSide: OdfPortSide? = null,
)

data class ConnectFiberCommand(
    val closureKind: ClosureKind,
    val closureId: UUID,
    val a: ConnectionPointCommand,
    val b: ConnectionPointCommand,
    val method: SpliceMethod = SpliceMethod.FUSION,
    val lossDb: Double? = null,
    val note: String? = null,
    /** Work order tempat pekerjaan ini dibukukan; kosong = kerja rutin tanpa tiket. */
    val workOrderId: UUID? = null,
)

/**
 * Pindah serat: dari helai yang bermasalah ke helai cadangan di kabel yang sama.
 *
 * [markSourceDamaged] hampir selalu benar — yang memaksa orang naik ke tiang
 * biasanya serat putus atau redamannya jeblok. Bisa dimatikan untuk pemindahan
 * yang sifatnya penataan (mis. merapikan core agar berurutan), sebab menandai
 * serat sehat sebagai rusak berarti mengubur kapasitas yang masih bisa dijual.
 */
data class MoveCoreCommand(
    val fromCoreId: UUID,
    val toCoreId: UUID,
    /** Tiket yang menaungi pemindahan; kosong = kerja darurat tanpa tiket. */
    val workOrderId: UUID? = null,
    /** Alasan singkat; ditulis ke catatan serat lama supaya terbaca di grid core. */
    val reason: String? = null,
    val markSourceDamaged: Boolean = true,
)

data class UpdateFiberConnectionCommand(
    val method: SpliceMethod,
    val lossDb: Double? = null,
    val note: String? = null,
    /**
     * Menempelkan work order yang tadinya belum diisi. Hanya menambah: sambungan
     * yang sudah punya WO tak bisa dipindahkan ke WO lain lewat jalan ini.
     */
    val workOrderId: UUID? = null,
)

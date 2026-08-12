package com.duluin.ftth.network.domain.model

import com.duluin.ftth.common.domain.UuidV7
import com.duluin.ftth.common.domain.error.ConflictException
import com.duluin.ftth.common.domain.error.ValidationException
import java.time.Instant
import java.util.UUID

/**
 * Tempat sebuah sambungan berada secara fisik — kotak yang dibuka teknisi saat
 * mau menyambung. Bukan sekadar label: closure inilah yang membatasi "satu titik
 * dipakai sekali", dan yang membuat pertanyaan "isi ODP ini apa saja" bisa
 * dijawab tanpa menyusuri seluruh jaringan.
 */
enum class ClosureKind(val label: String) {
    ODC("ODC"),
    ODP("ODP"),

    /** Sambungan di tengah jalur, bukan di simpul distribusi. */
    JOINT_BOX("Joint box"),

    /** Terminasi rapi di rak POP — tempat kabel luar bertemu patchcord. */
    ODF("ODF"),
    ;

    /**
     * Apakah di dalamnya ada splitter. Joint box TIDAK: isinya cuma tray dan
     * sambungan core-ke-core. Membedakannya di sini membuat penolakan "kaki
     * splitter di joint box" berbunyi sebagai aturan fisik, bukan sebagai
     * kapasitas nol yang kebetulan tak muat.
     */
    val hasSplitter: Boolean get() = this == ODC || this == ODP

    companion object {
        /**
         * Padanan kotak sambung untuk sebuah simpul jaringan; null bila simpul
         * itu memang bukan kotak yang bisa dibuka teknisi serat.
         *
         * POP, badan OLT, dan rumah pelanggan sengaja tak punya padanan: kabel
         * memang berhenti di sana, tapi tak ada selubung yang dikupas di
         * dalamnya. Jawaban null itulah yang menolak permintaan seperti "kupas
         * kabel ini di rumah pelanggan" tanpa perlu daftar larangan terpisah.
         */
        fun of(kind: NetworkNodeKind): ClosureKind? = when (kind) {
            NetworkNodeKind.ODC -> ODC
            NetworkNodeKind.ODP -> ODP
            NetworkNodeKind.JOINT_BOX -> JOINT_BOX
            NetworkNodeKind.ODF -> ODF
            NetworkNodeKind.SITE, NetworkNodeKind.OLT, NetworkNodeKind.CUSTOMER -> null
        }
    }
}

/**
 * Cara dua serat disatukan. Berpengaruh langsung ke redaman yang wajar: fusion
 * ±0,05 dB, mekanik ±0,3 dB, konektor ±0,5 dB. Disimpan supaya angka rugi hasil
 * ukur bisa dinilai "masuk akal atau tidak" tanpa menebak-nebak cara pasangnya.
 */
enum class SpliceMethod(val label: String, val typicalLossDb: Double) {
    FUSION("Fusion (las)", 0.05),
    MECHANICAL("Mekanik", 0.3),
    CONNECTOR("Konektor (patch)", 0.5),
}

/**
 * Jenis benda yang boleh jadi ujung sambungan.
 *
 * Dua keluarga: [CORE] menunjuk sehelai serat di dalam kabel, sisanya menunjuk
 * sebuah PORT pada perangkat. Pemisahan ini yang membuat konektivitas akhirnya
 * bisa ditelusuri — sebuah core masuk closure, disambung ke sesuatu, dan
 * "sesuatu" itu punya identitas yang jelas.
 */
enum class ConnectionPointKind(val label: String, val numbered: Boolean) {
    /** Sehelai serat kabel. */
    CORE("Core kabel", numbered = false),

    /** Port di panel ODF — bernomor. */
    ODF_PORT("Port ODF", numbered = true),

    /** Kaki masuk splitter; cuma ada satu per splitter. */
    SPLITTER_IN("Input splitter", numbered = false),

    /** Kaki keluar splitter — bernomor (1..rasio). */
    SPLITTER_OUT("Kaki splitter", numbered = true),

    /** Port PON di OLT; identitasnya sudah tunggal lewat id port-nya. */
    PON_PORT("PON port OLT", numbered = false),

    /** Perangkat pelanggan di ujung drop core. */
    ONU("ONU pelanggan", numbered = false),
}

/**
 * Satu ujung sambungan: benda apa, yang mana.
 *
 * Bentuknya dijaga di sini DAN di CHECK database (lihat V89) karena titik yang
 * menunjuk dua benda sekaligus membuat penelusuran jalur diam-diam bercabang —
 * kesalahan yang tak pernah terlihat sampai seseorang mencari sumber gangguan.
 */
data class ConnectionPoint(
    val kind: ConnectionPointKind,
    /** Terisi hanya untuk [ConnectionPointKind.CORE]. */
    val coreId: UUID? = null,
    /** Terisi untuk selain CORE: id splitter/ODF/OLT/ONU. */
    val nodeId: UUID? = null,
    /** Terisi hanya untuk titik bernomor (kaki splitter, port ODF). */
    val portNumber: Int? = null,
    /**
     * Sisi port ODF; wajib untuk [ConnectionPointKind.ODF_PORT] dan terlarang
     * untuk yang lain. Sisi ikut jadi bagian identitas titik karena satu port ODF
     * memang dipakai DUA sambungan — belakangnya ke core kabel luar, depannya ke
     * patchcord OLT. Tanpa ini, "satu titik dipakai sekali" akan melarang
     * sambungan kedua yang justru wajib ada.
     */
    val portSide: OdfPortSide? = null,
) {
    init {
        if (kind == ConnectionPointKind.CORE) {
            if (coreId == null) throw ValidationException("Titik core wajib menyebut core-nya")
            if (nodeId != null) throw ValidationException("Titik core tak boleh sekaligus menunjuk simpul")
        } else {
            if (nodeId == null) throw ValidationException("Titik ${kind.label} wajib menyebut simpulnya")
            if (coreId != null) throw ValidationException("Titik ${kind.label} tak boleh sekaligus menunjuk core")
        }
        if (kind.numbered) {
            if (portNumber == null || portNumber < 1) {
                throw ValidationException("${kind.label} wajib menyebut nomor port (mulai 1)")
            }
        } else if (portNumber != null) {
            throw ValidationException("${kind.label} tak bernomor port")
        }
        if (kind == ConnectionPointKind.ODF_PORT) {
            if (portSide == null) throw ValidationException("Port ODF wajib menyebut sisinya (belakang/depan)")
        } else if (portSide != null) {
            throw ValidationException("${kind.label} tak bersisi — sisi hanya ada pada port ODF")
        }
    }

    /** Uraian singkat untuk pesan galat, mis. "Kaki splitter 3", "Port ODF 7 depan". */
    val description: String
        get() = listOfNotNull(kind.label, portNumber?.toString(), portSide?.label?.lowercase()).joinToString(" ")

    companion object {
        fun core(coreId: UUID) = ConnectionPoint(ConnectionPointKind.CORE, coreId = coreId)

        fun node(kind: ConnectionPointKind, nodeId: UUID, portNumber: Int? = null) =
            ConnectionPoint(kind, nodeId = nodeId, portNumber = portNumber)

        /** Satu sisi sebuah port ODF — bentuk yang paling sering dipakai layar splicing. */
        fun odfPort(odfId: UUID, portNumber: Int, side: OdfPortSide) =
            ConnectionPoint(ConnectionPointKind.ODF_PORT, nodeId = odfId, portNumber = portNumber, portSide = side)
    }
}

/**
 * Sepasang titik yang benar-benar disambung orang di dalam sebuah closure.
 *
 * Inilah pemindahan pokok desain ini: konektivitas menempel ke CORE, bukan ke
 * kabel. Satu selubung 8 core yang melewati delapan ODP menghasilkan delapan
 * sambungan berbeda — kabelnya tetap satu, dan tak ada lagi dorongan menggambar
 * kabel palsu ODC→ODP satu per satu cuma demi mencatat hubungan.
 *
 * Kedua sisi SETARA: tak ada "dari" dan "ke". Arah cahaya bukan sifat sambungan,
 * melainkan hasil penelusuran dari OLT — memaksakan arah di sini hanya membuat
 * operator menebak-nebak mana yang harus ditaruh di kolom kiri.
 */
class FiberConnection private constructor(
    val id: UUID,
    val tenantId: UUID,
    val closureKind: ClosureKind,
    val closureId: UUID,
    a: ConnectionPoint,
    b: ConnectionPoint,
    method: SpliceMethod,
    lossDb: Double?,
    note: String?,
    workOrderId: UUID?,
    /**
     * Akun yang mencatat sambungan ini — di aplikasi lapangan, teknisi yang
     * membuka kotaknya. Null untuk baris warisan dari sebelum jejak ini ada;
     * bukan "tak ada yang mengerjakan", melainkan "tak tercatat".
     */
    val splicedBy: UUID?,
    val splicedAt: Instant,
) {
    /**
     * Kedua ujung sambungan. Praktis tetap seumur hidup barisnya — satu-satunya
     * yang boleh menggesernya adalah [moveCore], dan itu pun hanya menukar SERAT
     * yang ditunjuk, tak pernah jenis titik maupun nomor portnya.
     */
    var a: ConnectionPoint = a
        private set

    var b: ConnectionPoint = b
        private set

    var method: SpliceMethod = method
        private set

    /**
     * Work order tempat pekerjaan ini dibukukan. Null bukan cacat data: banyak
     * sambungan lahir saat pembangunan awal, jauh sebelum ada tiket.
     */
    var workOrderId: UUID? = workOrderId
        private set

    /** Rugi hasil ukur (dB). Null = belum diukur — bukan nol. */
    var lossDb: Double? = lossDb
        private set

    var note: String? = note
        private set

    /** Sisi seberang dari [point]; null bila titik itu bukan bagian sambungan ini. */
    fun opposite(point: ConnectionPoint): ConnectionPoint? = when (point) {
        a -> b
        b -> a
        else -> null
    }

    /** Core yang disentuh sambungan ini — nol, satu, atau dua helai. */
    val coreIds: List<UUID> get() = listOfNotNull(a.coreId, b.coreId)

    /**
     * Menukar serat yang dipegang sambungan ini: dari [fromCoreId] ke [toCoreId].
     *
     * Yang terjadi di lapangan saat sehelai serat putus bukan "bikin sambungan
     * baru" melainkan memindahkan pekerjaan yang SAMA ke helai cadangan di
     * selubung yang sama — kaki splitter, port ODF, dan kotaknya tak bergeser
     * sesenti pun, cuma seratnya yang diganti. Karena itu barisnya dipertahankan
     * beserta tiket, pelaksana, dan waktu aslinya: menghapus lalu membuat ulang
     * akan menghilangkan jawaban atas "sejak kapan jalur ini hidup" tepat pada
     * saat riwayat itu paling dibutuhkan.
     *
     * Hasil ukur redaman TIDAK dibawa — angka lama milik serat lama. Dibersihkan
     * di sini supaya tak ada yang menilai sambungan baru dengan bukti ukur yang
     * sudah tak berlaku.
     */
    fun moveCore(fromCoreId: UUID, toCoreId: UUID) {
        if (fromCoreId == toCoreId) throw ValidationException("Core asal dan tujuan sama")
        val moved = { point: ConnectionPoint ->
            if (point.coreId == fromCoreId) ConnectionPoint.core(toCoreId) else point
        }
        val nextA = moved(a)
        val nextB = moved(b)
        if (nextA == a && nextB == b) {
            throw ValidationException("Sambungan ini tak menyentuh core yang dipindah")
        }
        if (nextA == nextB) throw ConflictException("Core tujuan sudah jadi ujung seberang sambungan ini")
        a = nextA
        b = nextB
        lossDb = null
    }

    fun update(method: SpliceMethod, lossDb: Double?, note: String?) {
        this.method = method
        this.lossDb = validateLoss(lossDb)
        this.note = sanitizeNote(note)
    }

    /**
     * Membukukan sambungan ini ke sebuah work order — boleh menyusul, sebab
     * teknisi kerap menyambung dulu dan mengisi nomor tiketnya setelah turun dari
     * tangga.
     *
     * Yang TIDAK disediakan: melepas atau memindahkannya ke WO lain. Sambungan
     * ini bukti bahwa suatu pekerjaan pernah dilakukan; memindahkan buktinya ke
     * tiket lain menghapus jawaban atas satu-satunya pertanyaan yang benar-benar
     * ditanyakan saat gangguan berulang — "waktu itu siapa yang buka kotak ini,
     * dan dalam rangka apa".
     */
    fun attachWorkOrder(id: UUID) {
        val current = workOrderId
        if (current != null && current != id) {
            throw ConflictException("Sambungan ini sudah dibukukan ke work order lain")
        }
        workOrderId = id
    }

    companion object {
        const val MAX_NOTE_LENGTH = 200

        /**
         * Batas atas yang sengaja longgar. Bukan penilai mutu sambungan — cuma
         * penyaring salah ketik (mis. 250 alih-alih 2,50) supaya angka mustahil
         * tak ikut masuk anggaran redaman.
         */
        const val MAX_LOSS_DB = 999.0

        fun create(
            tenantId: UUID,
            closureKind: ClosureKind,
            closureId: UUID,
            a: ConnectionPoint,
            b: ConnectionPoint,
            method: SpliceMethod,
            lossDb: Double? = null,
            note: String? = null,
            workOrderId: UUID? = null,
            splicedBy: UUID? = null,
            splicedAt: Instant = Instant.now(),
        ): FiberConnection {
            if (a == b) throw ValidationException("Sebuah titik tak bisa disambung ke dirinya sendiri")
            return FiberConnection(
                id = UuidV7.generate(),
                tenantId = tenantId,
                closureKind = closureKind,
                closureId = closureId,
                a = a,
                b = b,
                method = method,
                lossDb = validateLoss(lossDb),
                note = sanitizeNote(note),
                workOrderId = workOrderId,
                splicedBy = splicedBy,
                splicedAt = splicedAt,
            )
        }

        @Suppress("LongParameterList")
        fun rehydrate(
            id: UUID,
            tenantId: UUID,
            closureKind: ClosureKind,
            closureId: UUID,
            a: ConnectionPoint,
            b: ConnectionPoint,
            method: SpliceMethod,
            lossDb: Double?,
            note: String?,
            workOrderId: UUID?,
            splicedBy: UUID?,
            splicedAt: Instant,
        ): FiberConnection = FiberConnection(
            id, tenantId, closureKind, closureId, a, b, method, lossDb, note, workOrderId, splicedBy, splicedAt,
        )

        private fun validateLoss(lossDb: Double?): Double? {
            val value = lossDb ?: return null
            if (!value.isFinite() || value < 0 || value > MAX_LOSS_DB) {
                throw ValidationException("Redaman sambungan tidak masuk akal: $value dB")
            }
            return value
        }

        private fun sanitizeNote(note: String?): String? {
            val trimmed = note?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            if (trimmed.length > MAX_NOTE_LENGTH) {
                throw ValidationException("Catatan sambungan maksimal $MAX_NOTE_LENGTH karakter")
            }
            return trimmed
        }
    }
}

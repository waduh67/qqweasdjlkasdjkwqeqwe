package com.duluin.ftth.network.domain.model

import com.duluin.ftth.common.domain.UuidV7
import com.duluin.ftth.common.domain.error.ValidationException
import com.duluin.ftth.common.domain.geo.Coordinate
import com.duluin.ftth.common.domain.geo.RoutePath
import java.util.UUID

/**
 * Peran kabel dalam hierarki distribusi, sekaligus pasangan simpul yang sah di
 * kedua ujungnya. Menyimpan aturan ini di enum mencegah data mustahil seperti
 * kabel drop yang menghubungkan dua OLT.
 *
 * JOINT_BOX sah di kedua sisi pada SEMUA jenis kabel, dan itu bukan kelonggaran
 * asal-asalan: kabel dijual per haspel, jadi ruas panjang apa pun cepat atau
 * lambat terpotong jadi beberapa kabel yang bertemu di kotak sambung — begitu
 * pula tiap perbaikan darurat. Melarangnya berarti memaksa operator menggambar
 * satu kabel utuh yang di lapangan sudah lama tak utuh.
 */
enum class CableType(
    val validFrom: Set<NetworkNodeKind>,
    val validTo: Set<NetworkNodeKind>,
) {
    /**
     * Ruas antar-POP atau antar-kabinet — tulang punggung yang tak membawa
     * pelanggan satu pun secara langsung.
     *
     * Banyak ISP lokal terbiasa menyebut feeder sebagai "backbone", dan selama
     * POP-nya cuma satu kedua istilah itu memang menunjuk kabel yang sama.
     * Bedanya baru terasa begitu POP kedua berdiri: kabel antar-POP tidak punya
     * ODC di ujungnya, jadi aturan feeder ("harus berakhir di kabinet") akan
     * menolaknya — dan operator terpaksa menggambar ODC bohongan supaya kabelnya
     * mau tersimpan. Jenis tersendiri ini yang menghapus dorongan itu.
     *
     * Kedua ujungnya harus SEDERAJAT — lihat [assertSameTier].
     */
    BACKBONE(
        setOf(
            NetworkNodeKind.SITE, NetworkNodeKind.OLT, NetworkNodeKind.ODF,
            NetworkNodeKind.ODC, NetworkNodeKind.JOINT_BOX,
        ),
        setOf(
            NetworkNodeKind.SITE, NetworkNodeKind.OLT, NetworkNodeKind.ODF,
            NetworkNodeKind.ODC, NetworkNodeKind.JOINT_BOX,
        ),
    ),

    /**
     * POP → ODC. Kabel berkapasitas besar dari POP ke kabinet distribusi.
     *
     * ODF sah di kedua sisi: di POP asal ia titik berangkat yang benar (kabel luar
     * berhenti di rak, bukan di badan OLT), dan di POP tujuan ia titik berhenti
     * feeder antar-POP. Ujung SITE/OLT tetap diterima untuk ISP yang rak-nya belum
     * ada — ODF memang opsional.
     */
    FEEDER(
        setOf(NetworkNodeKind.SITE, NetworkNodeKind.OLT, NetworkNodeKind.ODF, NetworkNodeKind.JOINT_BOX),
        setOf(NetworkNodeKind.ODC, NetworkNodeKind.ODF, NetworkNodeKind.JOINT_BOX),
    ),

    /** ODC → ODP, atau ODP → ODP saat ODP dirangkai berantai. */
    DISTRIBUTION(
        setOf(NetworkNodeKind.ODC, NetworkNodeKind.ODP, NetworkNodeKind.JOINT_BOX),
        setOf(NetworkNodeKind.ODP, NetworkNodeKind.JOINT_BOX),
    ),

    /** ODP → rumah pelanggan. */
    DROP(
        setOf(NetworkNodeKind.ODP, NetworkNodeKind.JOINT_BOX),
        setOf(NetworkNodeKind.CUSTOMER, NetworkNodeKind.JOINT_BOX),
    ),
    ;

    fun assertEndpoints(from: NetworkEndpoint, to: NetworkEndpoint) {
        if (from.kind !in validFrom) {
            throw ValidationException("Kabel $name tidak boleh berawal dari ${from.kind}, harus salah satu dari $validFrom")
        }
        if (to.kind !in validTo) {
            throw ValidationException("Kabel $name tidak boleh berakhir di ${to.kind}, harus salah satu dari $validTo")
        }
        if (from.ref == to.ref) throw ValidationException("Kabel tidak boleh berawal dan berakhir di simpul yang sama")
        if (this == BACKBONE) assertSameTier(from, to)
        assertPortShape(from)
        assertPortShape(to)
    }

    /**
     * Backbone menghubungkan yang SEDERAJAT: POP dengan POP, atau kabinet dengan
     * kabinet. Turun satu tingkat — POP ke kabinet — sudah punya namanya sendiri
     * sejak dulu, yaitu FEEDER, dan membiarkan dua nama untuk benda yang sama
     * membuat laporan panjang kabel per jenis kehilangan artinya.
     *
     * Joint box tak punya derajat: ia cuma tempat dua haspel bertemu, jadi ia
     * cocok di sisi mana pun tanpa ikut menentukan tingkat.
     */
    private fun assertSameTier(from: NetworkEndpoint, to: NetworkEndpoint) {
        val a = tierOf(from.kind)
        val b = tierOf(to.kind)
        if (a != null && b != null && a != b) {
            throw ValidationException(
                "Kabel BACKBONE menghubungkan simpul sederajat (POP ke POP, atau ODC ke ODC). " +
                    "Untuk ${from.kind} ke ${to.kind} pakai FEEDER.",
            )
        }
    }

    /** Tingkat hierarki sebuah simpul; null = tak bertingkat (joint box). */
    private fun tierOf(kind: NetworkNodeKind): String? = when (kind) {
        NetworkNodeKind.SITE, NetworkNodeKind.OLT, NetworkNodeKind.ODF -> "POP"
        NetworkNodeKind.ODC -> "KABINET"
        else -> null
    }

    /**
     * Port hanya boleh menempel di jenis simpul yang memang punya port fisik. Yang
     * WAJIB-ada-nya port pada kabel baru + batas kapasitas + okupansi ditegakkan di
     * CableService (butuh kapasitas simpul & data kabel lain); di sini cukup
     * memastikan port tidak salah tempel — mis. PON port di ujung ODP.
     */
    private fun assertPortShape(endpoint: NetworkEndpoint) {
        if (endpoint.ponPortId != null && endpoint.kind != NetworkNodeKind.OLT) {
            throw ValidationException("PON port hanya berlaku untuk ujung OLT, bukan ${endpoint.kind}")
        }
        endpoint.portNumber?.let { port ->
            if (endpoint.kind != NetworkNodeKind.ODC && endpoint.kind != NetworkNodeKind.ODP) {
                throw ValidationException("Nomor port hanya berlaku untuk ODC/ODP, bukan ${endpoint.kind}")
            }
            if (port < 1) throw ValidationException("Nomor port harus >= 1")
        }
    }
}

/**
 * Cara kabel terpasang di lapangan. Bukan hiasan katalog: inilah yang menentukan
 * siapa yang berangkat saat putus dan bawa apa. Kabel udara cukup tangga dan bisa
 * disambung sore itu juga; kabel tanam butuh galian, izin, dan sering bermalam.
 *
 * Sengaja NULLABLE di kabel (lihat V88): "belum disurvei" adalah keadaan yang
 * jujur dan berguna, sedangkan menebak "udara" untuk semua kabel lama melahirkan
 * data yang terlihat lengkap tapi menyesatkan.
 */
enum class CableInstallation(val label: String) {
    /** Digantung di tiang — milik sendiri atau numpang PLN/Telkom. */
    AERIAL("Udara (tiang)"),

    /** Ditanam langsung ke tanah tanpa pelindung duct. */
    BURIED("Tanam langsung"),

    /** Di dalam duct/HDPE — bisa ditarik ulang tanpa menggali seluruh jalur. */
    DUCT("Duct / HDPE"),
}

/**
 * Siapa pemilik ruas ini. Menentukan siapa yang boleh menyentuhnya saat gangguan
 * dan siapa yang menagih tiap bulan: pada ruas sewa, memotong-sambung sendiri
 * biasanya melanggar kontrak dan perbaikannya harus lewat pemiliknya.
 */
enum class CableOwnership(val label: String) {
    /** Dibangun & dimiliki sendiri. Default untuk kabel yang digambar di peta sendiri. */
    OWNED("Milik sendiri"),

    /** Sewa / dark fiber operator lain. Kekecualian yang harus ditandai sadar. */
    LEASED("Sewa"),
}

/**
 * Ruas kabel fiber beserta jalur fisiknya di peta.
 *
 * Panjang selalu diturunkan dari geometri (termasuk cadangan slack), tidak pernah
 * diinput manual — supaya total kebutuhan material yang dilaporkan tidak pernah
 * berbeda dari jalur yang benar-benar tergambar.
 *
 * Simpul yang disentuhnya adalah BARISAN, bukan sepasang ujung: satu selubung
 * distribusi berangkat dari ODC lalu dikupas di belasan ODP sepanjang jalan.
 * [from] dan [to] tetap ada dan tetap berarti persis seperti dulu — keduanya
 * kini DITURUNKAN dari ujung-ujung barisan itu, jadi tak mungkin lagi bertengkar
 * dengan daftar singgahannya. Lihat [CableAttachmentRole].
 */
class Cable private constructor(
    val id: UUID,
    val tenantId: UUID,
    code: String,
    name: String,
    cableType: CableType,
    coreCount: Int,
    route: RoutePath,
    attachments: List<CableAttachment>,
    status: AssetStatus,
    installation: CableInstallation?,
    ownership: CableOwnership,
) {
    /**
     * Kode yang tertulis di label selubung. Boleh diganti belakangan: ruas yang terlanjur
     * berkode buatan sistem harus bisa dirapikan ke penomoran perusahaan tanpa menggambar
     * ulang jalurnya — dan sebaliknya, kabel yang diberi kode salah ketik tak perlu dihapus.
     * Yang tak pernah berubah cuma [id]; kode itu label, bukan pengenal.
     */
    var code: String = code
        private set

    var name: String = name
        private set

    var cableType: CableType = cableType
        private set

    var coreCount: Int = coreCount
        private set

    var route: RoutePath = route
        private set

    /**
     * Simpul yang disinggahi kabel, TERURUT mengikuti arah gambar rute: elemen
     * pertama pangkal, terakhir ujung, sisanya singgahan di tengah bentang.
     * Urutan daftar inilah nomor urutnya — tak ada bidang `sequence` yang bisa
     * basi diam-diam saat ada singgahan disisipkan.
     */
    var attachments: List<CableAttachment> = attachments
        private set

    /** Pangkal kabel: singgahan pertama, selalu berperan [CableAttachmentRole.END]. */
    val from: NetworkEndpoint get() = attachments.first().node

    /** Ujung kabel: singgahan terakhir, selalu berperan [CableAttachmentRole.END]. */
    val to: NetworkEndpoint get() = attachments.last().node

    /** Singgahan di antara kedua ujung — yang dikupas di tengah, dan yang cuma lewat. */
    val waypoints: List<CableAttachment> get() = attachments.subList(1, attachments.size - 1)

    var status: AssetStatus = status
        private set

    /** Null = belum disurvei, bukan "tak ada". Lihat [CableInstallation]. */
    var installation: CableInstallation? = installation
        private set

    var ownership: CableOwnership = ownership
        private set

    /** Panjang material termasuk slack, dalam meter. */
    val lengthMeters: Double get() = route.withSlack()

    /**
     * [waypoints] null = JANGAN diubah, bukan "kosongkan".
     *
     * Formulir kabel di peta cuma menanyakan kedua ujung; kalau daftar kosong
     * dari sana diperlakukan sebagai kehendak, sekali orang merapikan nama kabel
     * seluruh catatan "dikupas di ODP-3 sampai ODP-11" ikut terhapus tanpa ada
     * yang meminta. Pemanggil yang memang mengelola singgahan mengirim daftarnya
     * secara sadar — termasuk daftar kosong.
     */
    @Suppress("LongParameterList")
    fun update(
        code: String,
        name: String,
        cableType: CableType,
        coreCount: Int,
        route: RoutePath,
        from: NetworkEndpoint,
        to: NetworkEndpoint,
        waypoints: List<CableWaypoint>? = null,
        status: AssetStatus,
        installation: CableInstallation?,
        ownership: CableOwnership,
    ) {
        this.attachments = buildAttachments(cableType, from, to, waypoints ?: currentWaypoints(), attachments)
        this.code = AssetNaming.code(code, "kabel")
        this.name = AssetNaming.name(name, "kabel")
        this.cableType = cableType
        this.coreCount = validateCoreCount(coreCount)
        this.route = route
        this.status = status
        this.installation = installation
        this.ownership = ownership
    }

    private fun currentWaypoints(): List<CableWaypoint> = waypoints.map { CableWaypoint(it.node.ref, it.role) }

    /**
     * Menandai kabel yang fisiknya masih terpasang tapi sudah tak dipakai —
     * lihat [AssetStatus.ABANDONED].
     *
     * Sengaja terpisah dari [update] yang meminta sembilan bidang sekaligus:
     * "pelanggannya cabut" adalah SATU keputusan, dan memaksa orang menyunting
     * seluruh formulir kabel untuk menyatakannya membuat tindakan ini jarang
     * dilakukan — lalu peta penuh kabel mati yang mengaku aktif.
     *
     * Idempoten, dan tak menghapus apa pun: rutenya, panjangnya, dan barisan
     * core-nya tetap utuh supaya kabel bekas ini bisa dihidupkan lagi kalau
     * ternyata rumah yang sama berlangganan lagi — hal yang sering terjadi.
     */
    fun abandon() {
        status = AssetStatus.ABANDONED
    }

    /**
     * Menempelkan ujung kabel yang menyambung ke [ref] pada [coord] — dipakai saat
     * simpul (OLT/ODC/ODP/site/pelanggan) dipindah di peta. HANYA titik ujung yang
     * digeser; tikungan di tengah tak disentuh dan panjang otomatis dihitung ulang
     * lewat [lengthMeters]. Mengembalikan `true` bila ada titik yang benar-benar
     * bergeser, agar pemanggil bisa melewati penyimpanan yang tak perlu. Idempoten:
     * ujung yang sudah pas di [coord] dibiarkan. Sebuah kabel tak pernah berawal &
     * berakhir di simpul sama (dijaga [CableType.assertEndpoints]), jadi paling
     * banyak satu ujung tergeser per panggilan.
     */
    fun snapEndpointTo(ref: NetworkNodeRef, coord: Coordinate): Boolean {
        var changed = false
        if (from.ref == ref && route.start != coord) {
            route = route.withStart(coord)
            changed = true
        }
        if (to.ref == ref && route.end != coord) {
            route = route.withEnd(coord)
            changed = true
        }
        return changed
    }

    companion object {
        const val MAX_CORE_COUNT = 288

        @Suppress("LongParameterList")
        fun create(
            tenantId: UUID,
            code: String,
            name: String,
            cableType: CableType,
            coreCount: Int,
            route: RoutePath,
            from: NetworkEndpoint,
            to: NetworkEndpoint,
            waypoints: List<CableWaypoint> = emptyList(),
            status: AssetStatus = AssetStatus.ACTIVE,
            installation: CableInstallation? = null,
            ownership: CableOwnership = CableOwnership.OWNED,
        ): Cable = Cable(
            id = UuidV7.generate(),
            tenantId = tenantId,
            code = AssetNaming.code(code, "kabel"),
            name = AssetNaming.name(name, "kabel"),
            cableType = cableType,
            coreCount = validateCoreCount(coreCount),
            route = route,
            attachments = buildAttachments(cableType, from, to, waypoints, emptyList()),
            status = status,
            installation = installation,
            ownership = ownership,
        )

        @Suppress("LongParameterList")
        fun rehydrate(
            id: UUID,
            tenantId: UUID,
            code: String,
            name: String,
            cableType: CableType,
            coreCount: Int,
            route: RoutePath,
            attachments: List<CableAttachment>,
            status: AssetStatus,
            installation: CableInstallation?,
            ownership: CableOwnership,
        ): Cable = Cable(
            id, tenantId, code, name, cableType, coreCount, route, attachments, status, installation, ownership,
        )

        /**
         * Merakit barisan singgahan dari kedua ujung + singgahan tengah, sekaligus
         * menegakkan bentuknya: ujung selalu di kepala & ekor daftar, dan satu
         * simpul cuma boleh muncul sekali.
         *
         * Identitas singgahan lama dipertahankan lewat [existing] supaya menyunting
         * kabel tidak menghapus-lalu-membuat-ulang baris yang sebenarnya tak
         * berubah — id yang berganti-ganti membuat riwayat & rujukan ke singgahan
         * jadi tak bisa dipercaya.
         */
        private fun buildAttachments(
            cableType: CableType,
            from: NetworkEndpoint,
            to: NetworkEndpoint,
            waypoints: List<CableWaypoint>,
            existing: List<CableAttachment>,
        ): List<CableAttachment> {
            cableType.assertEndpoints(from, to)
            val keptIds = existing.associate { it.node.id to it.id }
            val result = buildList {
                add(attachmentOf(from, CableAttachmentRole.END, keptIds))
                waypoints.forEach { add(attachmentOf(NetworkEndpoint.of(it.node), it.role, keptIds)) }
                add(attachmentOf(to, CableAttachmentRole.END, keptIds))
            }
            val duplicated = result.groupingBy { it.node.id }.eachCount().filterValues { it > 1 }
            if (duplicated.isNotEmpty()) {
                throw ValidationException(
                    "Satu simpul tak boleh muncul dua kali pada kabel yang sama — " +
                        "kabel yang masuk lalu keluar dari kotak yang sama tetap satu singgahan.",
                )
            }
            return result
        }

        private fun attachmentOf(
            node: NetworkEndpoint,
            role: CableAttachmentRole,
            keptIds: Map<UUID, UUID>,
        ): CableAttachment = CableAttachment(
            id = keptIds[node.id] ?: UuidV7.generate(),
            node = node,
            role = role,
        )

        private fun validateCoreCount(coreCount: Int): Int {
            if (coreCount !in 1..MAX_CORE_COUNT) {
                throw ValidationException("Jumlah core harus 1-$MAX_CORE_COUNT")
            }
            return coreCount
        }
    }
}

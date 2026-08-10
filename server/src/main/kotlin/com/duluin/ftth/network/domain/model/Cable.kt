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
 */
enum class CableType(
    val validFrom: Set<NetworkNodeKind>,
    val validTo: Set<NetworkNodeKind>,
) {
    /** OLT → ODC. Kabel berkapasitas besar dari POP ke kabinet distribusi. */
    FEEDER(setOf(NetworkNodeKind.SITE, NetworkNodeKind.OLT), setOf(NetworkNodeKind.ODC)),

    /** ODC → ODP, atau ODP → ODP saat ODP dirangkai berantai. */
    DISTRIBUTION(setOf(NetworkNodeKind.ODC, NetworkNodeKind.ODP), setOf(NetworkNodeKind.ODP)),

    /** ODP → rumah pelanggan. */
    DROP(setOf(NetworkNodeKind.ODP), setOf(NetworkNodeKind.CUSTOMER)),
    ;

    fun assertEndpoints(from: NetworkEndpoint, to: NetworkEndpoint) {
        if (from.kind !in validFrom) {
            throw ValidationException("Kabel $name tidak boleh berawal dari ${from.kind}, harus salah satu dari $validFrom")
        }
        if (to.kind !in validTo) {
            throw ValidationException("Kabel $name tidak boleh berakhir di ${to.kind}, harus salah satu dari $validTo")
        }
        if (from.ref == to.ref) throw ValidationException("Kabel tidak boleh berawal dan berakhir di simpul yang sama")
        assertPortShape(from)
        assertPortShape(to)
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
 */
class Cable private constructor(
    val id: UUID,
    val tenantId: UUID,
    val code: String,
    name: String,
    cableType: CableType,
    coreCount: Int,
    route: RoutePath,
    from: NetworkEndpoint,
    to: NetworkEndpoint,
    status: AssetStatus,
    installation: CableInstallation?,
    ownership: CableOwnership,
) {
    var name: String = name
        private set

    var cableType: CableType = cableType
        private set

    var coreCount: Int = coreCount
        private set

    var route: RoutePath = route
        private set

    var from: NetworkEndpoint = from
        private set

    var to: NetworkEndpoint = to
        private set

    var status: AssetStatus = status
        private set

    /** Null = belum disurvei, bukan "tak ada". Lihat [CableInstallation]. */
    var installation: CableInstallation? = installation
        private set

    var ownership: CableOwnership = ownership
        private set

    /** Panjang material termasuk slack, dalam meter. */
    val lengthMeters: Double get() = route.withSlack()

    @Suppress("LongParameterList")
    fun update(
        name: String,
        cableType: CableType,
        coreCount: Int,
        route: RoutePath,
        from: NetworkEndpoint,
        to: NetworkEndpoint,
        status: AssetStatus,
        installation: CableInstallation?,
        ownership: CableOwnership,
    ) {
        cableType.assertEndpoints(from, to)
        this.name = AssetNaming.name(name, "kabel")
        this.cableType = cableType
        this.coreCount = validateCoreCount(coreCount)
        this.route = route
        this.from = from
        this.to = to
        this.status = status
        this.installation = installation
        this.ownership = ownership
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
            status: AssetStatus = AssetStatus.ACTIVE,
            installation: CableInstallation? = null,
            ownership: CableOwnership = CableOwnership.OWNED,
        ): Cable {
            cableType.assertEndpoints(from, to)
            return Cable(
                id = UuidV7.generate(),
                tenantId = tenantId,
                code = AssetNaming.code(code, "kabel"),
                name = AssetNaming.name(name, "kabel"),
                cableType = cableType,
                coreCount = validateCoreCount(coreCount),
                route = route,
                from = from,
                to = to,
                status = status,
                installation = installation,
                ownership = ownership,
            )
        }

        @Suppress("LongParameterList")
        fun rehydrate(
            id: UUID,
            tenantId: UUID,
            code: String,
            name: String,
            cableType: CableType,
            coreCount: Int,
            route: RoutePath,
            from: NetworkEndpoint,
            to: NetworkEndpoint,
            status: AssetStatus,
            installation: CableInstallation?,
            ownership: CableOwnership,
        ): Cable = Cable(
            id, tenantId, code, name, cableType, coreCount, route, from, to, status, installation, ownership,
        )

        private fun validateCoreCount(coreCount: Int): Int {
            if (coreCount !in 1..MAX_CORE_COUNT) {
                throw ValidationException("Jumlah core harus 1-$MAX_CORE_COUNT")
            }
            return coreCount
        }
    }
}

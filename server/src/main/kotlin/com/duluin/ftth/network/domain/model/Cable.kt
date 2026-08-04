package com.duluin.ftth.network.domain.model

import com.duluin.ftth.common.domain.UuidV7
import com.duluin.ftth.common.domain.error.ValidationException
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

    /** Panjang material termasuk slack, dalam meter. */
    val lengthMeters: Double get() = route.withSlack()

    fun update(
        name: String,
        cableType: CableType,
        coreCount: Int,
        route: RoutePath,
        from: NetworkEndpoint,
        to: NetworkEndpoint,
        status: AssetStatus,
    ) {
        cableType.assertEndpoints(from, to)
        this.name = AssetNaming.name(name, "kabel")
        this.cableType = cableType
        this.coreCount = validateCoreCount(coreCount)
        this.route = route
        this.from = from
        this.to = to
        this.status = status
    }

    companion object {
        const val MAX_CORE_COUNT = 288

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
        ): Cable = Cable(id, tenantId, code, name, cableType, coreCount, route, from, to, status)

        private fun validateCoreCount(coreCount: Int): Int {
            if (coreCount !in 1..MAX_CORE_COUNT) {
                throw ValidationException("Jumlah core harus 1-$MAX_CORE_COUNT")
            }
            return coreCount
        }
    }
}

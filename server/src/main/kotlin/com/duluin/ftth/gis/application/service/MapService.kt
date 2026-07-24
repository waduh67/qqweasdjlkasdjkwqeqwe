package com.duluin.ftth.gis.application.service

import com.duluin.ftth.common.domain.error.NotFoundException
import com.duluin.ftth.common.domain.geo.Coordinate
import com.duluin.ftth.common.security.CurrentUserProvider
import com.duluin.ftth.common.security.areaScope
import com.duluin.ftth.customer.CustomerApi
import com.duluin.ftth.customer.CustomerRef
import com.duluin.ftth.customer.OdpOccupant
import com.duluin.ftth.gis.application.port.inbound.AffectedCustomer
import com.duluin.ftth.gis.application.port.inbound.BlastRadiusView
import com.duluin.ftth.gis.application.port.inbound.CableCutView
import com.duluin.ftth.gis.application.port.inbound.CustomerTrace
import com.duluin.ftth.gis.application.port.inbound.ImpactCause
import com.duluin.ftth.gis.application.port.inbound.ImpactedCable
import com.duluin.ftth.gis.application.port.inbound.ImpactedOverlay
import com.duluin.ftth.gis.application.port.inbound.MapQuery
import com.duluin.ftth.monitoring.AlarmImpact
import com.duluin.ftth.gis.application.port.inbound.OdpInspection
import com.duluin.ftth.gis.application.port.inbound.SeveredCable
import com.duluin.ftth.gis.application.port.inbound.SiteInspection
import com.duluin.ftth.gis.application.port.inbound.SiteOlt
import com.duluin.ftth.gis.application.port.inbound.TraceHop
import com.duluin.ftth.gis.application.port.inbound.UpstreamView
import com.duluin.ftth.monitoring.MonitoringApi
import com.duluin.ftth.network.NetworkApi
import com.duluin.ftth.network.UpstreamPath
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID
import kotlin.math.roundToInt

@Service
@Transactional(readOnly = true)
class MapService(
    private val networkApi: NetworkApi,
    private val customerApi: CustomerApi,
    private val monitoringApi: MonitoringApi,
    private val currentUser: CurrentUserProvider,
) : MapQuery {

    /**
     * Menggabungkan tile jaringan dan tile pelanggan. Penyambungan byte MVT sah
     * secara protobuf: `layers` adalah repeated field, jadi hasilnya satu tile
     * berisi seluruh layer dari kedua module.
     */
    override fun renderTile(z: Int, x: Int, y: Int): ByteArray {
        val areaIds = currentUser.current().areaScope()
        return networkApi.renderMapTile(z, x, y, areaIds) + customerApi.renderMapTile(z, x, y, areaIds)
    }

    override fun inspectOdp(odpId: UUID): OdpInspection {
        val odp = networkApi.requireOdp(odpId)
        val upstream = networkApi.upstreamOf(odpId)
        val occupants = customerApi.findOccupantsOfOdp(odpId)
        val occupiedPorts = occupants.mapTo(HashSet()) { it.portNumber }

        return OdpInspection(
            odpId = odp.id,
            code = odp.code,
            name = odp.name,
            location = odp.location,
            capacity = odp.capacity,
            usedPorts = occupiedPorts.size,
            availablePortNumbers = (1..odp.capacity).filterNot { it in occupiedPorts },
            utilizationPercent = percentage(occupiedPorts.size, odp.capacity),
            upstream = upstream.toView(),
            occupants = occupants,
        )
    }

    /**
     * Menelusuri jalur seorang pelanggan ke hulu.
     *
     * Pelanggan yang ONU-nya belum terpasang tetap dikembalikan — dengan hulu
     * kosong. Justru inilah yang ingin dilihat operator: pelanggan yang terdaftar
     * tapi belum tersambung ke jaringan.
     */
    override fun traceCustomer(customerId: UUID): CustomerTrace {
        val customer = customerApi.findCustomer(customerId)
            ?: throw NotFoundException("Pelanggan $customerId tidak ditemukan")

        val placement = customerApi.findPlacementOf(customerId)
        val upstream = placement?.let { networkApi.upstreamOf(it.odpId) }

        return CustomerTrace(
            customerId = customer.id,
            customerCode = customer.code,
            customerName = customer.name,
            location = customer.location,
            onuSerialNumber = placement?.onuSerialNumber,
            onuStatus = placement?.onuStatus,
            installRxPowerDbm = placement?.installRxPowerDbm,
            opticalHealth = placement?.opticalHealth,
            odpPortNumber = placement?.portNumber,
            upstream = upstream?.toView(),
            estimatedLossDb = upstream?.let { estimateLoss(it, customer.location) },
            hops = buildHops(customer.location, upstream),
        )
    }

    /**
     * Menyusun kabel-kabel yang hilirnya bermasalah dari alarm hidup.
     *
     * Alur komposisinya: alarm (monitoring) → entitas terdampak. Untuk alarm ONU,
     * dipetakan ke pelanggan & ODP-nya (customer) — sebab kabel drop berujung di
     * pelanggan dan kabel distribusi di ODP. Simpul terdampak lalu dicocokkan ke
     * kabel yang menyentuhnya (network). Keparahan tiap kabel diambil dari ujung
     * terdampak yang paling parah. Tidak ada module yang menyentuh tabel milik
     * module lain — semuanya lewat kontrak publik.
     */
    override fun impactedCables(): ImpactedOverlay {
        val impacts = monitoringApi.activeImpacts()
        if (impacts.isEmpty()) return ImpactedOverlay(emptyList())

        // Keparahan per simpul (id perangkat/pelanggan), diambil yang tertinggi,
        // beserta daftar alarm penyebabnya untuk menjawab "kenapa merah" saat diklik.
        val nodeSeverity = HashMap<UUID, Int>()
        val nodeCauses = HashMap<UUID, MutableList<ImpactCause>>()
        fun bump(id: UUID, severity: String, cause: ImpactCause) {
            val rank = severityRank(severity)
            if (rank > (nodeSeverity[id] ?: -1)) nodeSeverity[id] = rank
            nodeCauses.getOrPut(id) { mutableListOf() }.add(cause)
        }

        // Alarm ONU → pelanggan + ODP-nya membawa keparahan & penyebabnya.
        val onuImpacts = impacts.filter { it.entityType == "ONU" }.associateBy { it.entityId }
        if (onuImpacts.isNotEmpty()) {
            customerApi.placementsForOnus(onuImpacts.keys).forEach { placement ->
                val impact = onuImpacts[placement.onuId] ?: return@forEach
                val cause = impact.toCause()
                bump(placement.customerId, impact.severity, cause)
                placement.odpId?.let { bump(it, impact.severity, cause) }
            }
        }
        // Alarm perangkat (ODP/ODC/OLT) langsung membawa id perangkatnya.
        impacts.filter { it.entityType != "ONU" && it.entityType != "COLLECTOR" }
            .forEach { bump(it.entityId, it.severity, it.toCause()) }

        // Blast radius: OLT/ODC yang mati menjalar ke seluruh perangkat di bawahnya,
        // sehingga feeder, distribusi, dan drop di hilirnya ikut merah. Penyebabnya
        // diwarisi dari alarm hulu itu sendiri, jadi kabel di hilir menjawab "kenapa
        // merah" dengan menunjuk perangkat hulu yang modar.
        val upstreamOutages = impacts.filter { it.entityType == "OLT" || it.entityType == "ODC" }
        if (upstreamOutages.isNotEmpty()) {
            val oltImpactIds = upstreamOutages.filter { it.entityType == "OLT" }.mapTo(HashSet()) { it.entityId }
            val odcImpactIds = upstreamOutages.filter { it.entityType == "ODC" }.mapTo(HashSet()) { it.entityId }
            val downstream = networkApi.downstreamDeviceIds(oltImpactIds, odcImpactIds)
            val downstreamNodes = downstream.odcIds + downstream.odpIds
            upstreamOutages.forEach { outage ->
                val cause = outage.toCause()
                downstreamNodes.forEach { bump(it, outage.severity, cause) }
            }
        }

        if (nodeSeverity.isEmpty()) return ImpactedOverlay(emptyList())

        val cables = networkApi.cablesTouchingNodes(nodeSeverity.keys).mapNotNull { cable ->
            val rank = maxOf(nodeSeverity[cable.fromId] ?: -1, nodeSeverity[cable.toId] ?: -1)
            if (rank < 0) return@mapNotNull null
            val causes = (nodeCauses[cable.fromId].orEmpty() + nodeCauses[cable.toId].orEmpty()).distinct()
            ImpactedCable(
                id = cable.id,
                code = cable.code,
                cableType = cable.cableType,
                severity = severityName(rank),
                points = cable.points,
                causes = causes,
            )
        }
        return ImpactedOverlay(cables)
    }

    /**
     * Menyusun blast radius sebuah ODC. Satu ODC bercabang ke sekumpulan kecil
     * ODP, jadi penghuni diambil per ODP (masing-masing dalam query tetap) lalu
     * diratakan — ini panel on-demand, bukan jalur render yang panas. Kode ODP
     * dibawa dari ref-nya supaya tiap pelanggan tahu menggantung di mana.
     */
    override fun blastRadius(odcId: UUID): BlastRadiusView {
        val odc = networkApi.requireOdc(odcId)
        val odpIds = networkApi.downstreamDeviceIds(emptySet(), setOf(odcId)).odpIds
        val odpCodeById = networkApi.findOdpsByIds(odpIds).associate { it.id to it.code }

        val customers = odpIds.flatMap { odp ->
            val odpCode = odpCodeById[odp] ?: "?"
            customerApi.findOccupantsOfOdp(odp).map { it.toAffected(odpCode) }
        }.sortedWith(compareBy({ it.odpCode }, { it.name }))

        return BlastRadiusView(
            odcId = odc.id,
            code = odc.code,
            name = odc.name,
            energized = odc.energized,
            odpCount = odpIds.size,
            customerCount = customers.size,
            // LOS/OFFLINE = sudah kehilangan layanan sungguhan, bukan sekadar potensi.
            downCount = customers.count { it.onuStatus == "LOS" || it.onuStatus == "OFFLINE" },
            customers = customers,
        )
    }

    /**
     * Menyusun simulasi putus sebuah kabel. Network memberi subpohon topologis
     * yang lenyap (ODC/ODP/pelanggan di hilir + geometri kabel); di sini subpohon
     * itu diterjemahkan ke pelanggan nyata: penghuni tiap ODP hilir plus — untuk
     * putus kabel drop yang tak menyisakan ODP — pelanggan di ujung kabel langsung.
     */
    override fun cutBlastRadius(cableId: UUID): CableCutView {
        val impact = networkApi.cutImpact(cableId)

        val odpCodeById = networkApi.findOdpsByIds(impact.odpIds).associate { it.id to it.code }
        val occupantCustomers = impact.odpIds.flatMap { odp ->
            val odpCode = odpCodeById[odp] ?: "?"
            customerApi.findOccupantsOfOdp(odp).map { it.toAffected(odpCode) }
        }

        // Putus kabel drop menyambar satu pelanggan langsung — tanpa ODP di hilir,
        // jadi diambil dari ujung CUSTOMER kabel lalu disatukan tanpa duplikat.
        val seenCustomerIds = occupantCustomers.mapTo(HashSet()) { it.customerId }
        val directCustomers = impact.customerIds
            .takeIf { it.isNotEmpty() }
            ?.let { customerApi.findCustomersByIds(it) }
            .orEmpty()
            .filter { it.id !in seenCustomerIds }
            .map { it.toAffected() }

        val customers = (occupantCustomers + directCustomers)
            .sortedWith(compareBy({ it.odpCode }, { it.name }))

        return CableCutView(
            cableId = impact.cableId,
            cableCode = impact.cableCode,
            cableType = impact.cableType,
            severedRootKind = impact.severedRootKind,
            odcCount = impact.odcIds.size,
            odpCount = impact.odpIds.size,
            customerCount = customers.size,
            downCount = customers.count { it.onuStatus == "LOS" || it.onuStatus == "OFFLINE" },
            customers = customers,
            severedCables = impact.severedCables.map {
                SeveredCable(id = it.id, code = it.code, cableType = it.cableType, points = it.points)
            },
        )
    }

    /**
     * Merekap sebuah site. OLT-nya diambil langsung; ODC/ODP di hilir dihitung
     * lewat primitif [NetworkApi.downstreamDeviceIds] (site → OLT → PON → ODC →
     * ODP), dan jumlah pelanggan lewat satu query hitung agregat customer — bukan
     * memuat tiap penghuni, karena satu site bisa menaungi ribuan sambungan.
     */
    override fun inspectSite(siteId: UUID): SiteInspection {
        val site = networkApi.findSite(siteId) ?: throw NotFoundException("Site $siteId tidak ditemukan")
        val olts = networkApi.oltsAtSite(siteId)
        val downstream = networkApi.downstreamDeviceIds(olts.mapTo(HashSet()) { it.id }, emptySet())
        val customerCount = customerApi.countOccupantsByOdp(downstream.odpIds).values.sum()

        return SiteInspection(
            siteId = site.id,
            code = site.code,
            name = site.name,
            address = site.address,
            location = site.location,
            oltCount = olts.size,
            odcCount = downstream.odcIds.size,
            odpCount = downstream.odpIds.size,
            customerCount = customerCount.toInt(),
            olts = olts.map { SiteOlt(id = it.id, code = it.code, name = it.name, vendor = it.vendor, active = it.active) },
        )
    }

    private fun OdpOccupant.toAffected(odpCode: String) = AffectedCustomer(
        customerId = customerId,
        code = customerCode,
        name = customerName,
        phone = phone,
        odpCode = odpCode,
        onuStatus = onuStatus,
        opticalHealth = opticalHealth,
    )

    /**
     * Pelanggan di ujung kabel drop yang diputus. Statusnya tak diketahui dari
     * sini (tak lewat ODP), tapi kepastiannya jelas: kabelnya putus, layanannya
     * hilang. Diberi tanda "—" untuk membedakannya dari penghuni ODP yang berstatus.
     */
    private fun CustomerRef.toAffected() = AffectedCustomer(
        customerId = id,
        code = code,
        name = name,
        phone = phone,
        odpCode = "—",
        onuStatus = "UNKNOWN",
        opticalHealth = "UNKNOWN",
    )

    private fun severityRank(severity: String): Int = when (severity) {
        "CRITICAL" -> 2
        "WARNING" -> 1
        else -> 0
    }

    private fun severityName(rank: Int): String = when (rank) {
        2 -> "CRITICAL"
        1 -> "WARNING"
        else -> "INFO"
    }

    private fun AlarmImpact.toCause() = ImpactCause(label = label, kind = kind, severity = severity)

    private fun UpstreamPath.toView() = UpstreamView(
        odcCode = odc?.code,
        odcName = odc?.name,
        ponPortLabel = ponPort?.code,
        oltCode = olt?.code,
        oltName = olt?.name,
        siteCode = site?.code,
        siteName = site?.name,
        complete = complete,
        splitterLossDb = splitterLossDb,
    )

    /**
     * Rugi splitter ditambah redaman serat menurut jarak garis lurus ODP→rumah.
     * Koefisien 0,35 dB/km adalah angka lazim untuk 1310 nm; jarak sebenarnya
     * selalu lebih panjang dari garis lurus, jadi ini batas bawah yang optimistis.
     */
    private fun estimateLoss(upstream: UpstreamPath, customerLocation: Coordinate): Double {
        val distanceKm = upstream.odp.location.distanceTo(customerLocation) / 1_000
        return upstream.splitterLossDb + distanceKm * FIBER_LOSS_DB_PER_KM
    }

    private fun buildHops(customerLocation: Coordinate, upstream: UpstreamPath?): List<TraceHop> = buildList {
        add(TraceHop("CUSTOMER", "", "Rumah pelanggan", customerLocation))
        upstream ?: return@buildList
        add(TraceHop("ODP", upstream.odp.code, upstream.odp.name, upstream.odp.location))
        upstream.odc?.let { add(TraceHop("ODC", it.code, it.name, null)) }
        upstream.ponPort?.let { add(TraceHop("PON_PORT", it.code, it.name, null)) }
        upstream.olt?.let { add(TraceHop("OLT", it.code, it.name, null)) }
        upstream.site?.let { add(TraceHop("SITE", it.code, it.name, null)) }
    }

    private fun percentage(used: Int, total: Int): Int =
        if (total == 0) 0 else (used.toDouble() / total * 100).roundToInt()

    private companion object {
        const val FIBER_LOSS_DB_PER_KM = 0.35
    }
}

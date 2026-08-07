package com.duluin.ftth.gis.application.service

import com.duluin.ftth.bng.BngApi
import com.duluin.ftth.bng.SubscriberSessionRef
import com.duluin.ftth.common.domain.error.NotFoundException
import com.duluin.ftth.common.domain.geo.Coordinate
import com.duluin.ftth.common.security.CurrentUserProvider
import com.duluin.ftth.common.security.areaScope
import com.duluin.ftth.customer.CustomerApi
import com.duluin.ftth.customer.CustomerRef
import com.duluin.ftth.customer.OdpOccupant
import com.duluin.ftth.gis.application.port.inbound.AffectedCustomer
import com.duluin.ftth.gis.application.port.inbound.BlastRadiusView
import com.duluin.ftth.gis.application.port.inbound.BrasHopView
import com.duluin.ftth.gis.application.port.inbound.CableCutView
import com.duluin.ftth.gis.application.port.inbound.CustomerTrace
import com.duluin.ftth.gis.application.port.inbound.ImpactCause
import com.duluin.ftth.gis.application.port.inbound.ImpactedCable
import com.duluin.ftth.gis.application.port.inbound.ImpactedNode
import com.duluin.ftth.gis.application.port.inbound.ImpactedOverlay
import com.duluin.ftth.gis.application.port.inbound.MapQuery
import com.duluin.ftth.monitoring.AlarmImpact
import com.duluin.ftth.gis.application.port.inbound.NeighborView
import com.duluin.ftth.gis.application.port.inbound.OdpInspection
import com.duluin.ftth.gis.application.port.inbound.OdpUtilization
import com.duluin.ftth.gis.application.port.inbound.OltOnuList
import com.duluin.ftth.gis.application.port.inbound.OltOnuRow
import com.duluin.ftth.gis.application.port.inbound.PonOdcBranch
import com.duluin.ftth.gis.application.port.inbound.PonPortInspection
import com.duluin.ftth.gis.application.port.inbound.SeveredCable
import com.duluin.ftth.gis.application.port.inbound.SiteInspection
import com.duluin.ftth.gis.application.port.inbound.SiteOlt
import com.duluin.ftth.gis.application.port.inbound.SubscriberNeighbors
import com.duluin.ftth.gis.application.port.inbound.UtilizationHeatmap
import com.duluin.ftth.gis.application.port.inbound.TraceHop
import com.duluin.ftth.gis.application.port.inbound.UpstreamView
import com.duluin.ftth.monitoring.MonitoringApi
import com.duluin.ftth.monitoring.OnuLiveMetric
import com.duluin.ftth.network.NetworkApi
import com.duluin.ftth.network.UpstreamPath
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.Locale
import java.util.UUID
import kotlin.math.roundToInt

@Service
@Transactional(readOnly = true)
class MapService(
    private val networkApi: NetworkApi,
    private val customerApi: CustomerApi,
    private val monitoringApi: MonitoringApi,
    private val bngApi: BngApi,
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
        // Bacaan optik HIDUP ONU pelanggan (beda dari redaman baseline saat instalasi);
        // null bila ONU belum terpasang atau belum pernah terbaca monitoring.
        val live = placement?.let { monitoringApi.latestMetricsByOnuIds(setOf(it.onuId))[it.onuId] }
        // Identitas jaringan + sesi PPPoE terkini — puncak jalur, di atas OLT.
        val bras = bngApi.findSubscriberSession(customerId)

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
            bras = bras?.toHopView(),
            liveOnuStatus = live?.status,
            liveRxPowerDbm = live?.rxPowerDbm,
            distanceMeters = live?.distanceMeters,
            hops = buildHops(customer.location, upstream, bras, live),
        )
    }

    /**
     * Menyusun tetangga sejalur seorang pelanggan.
     *
     * Se-ODP diambil langsung dari penghuni ODP-nya. Se-PON diambil dari seluruh
     * ODP di bawah PON port yang sama (network memberi daftar ODP-nya, customer
     * memberi penghuni tiap ODP) — superset yang memang mencakup se-ODP. Kondisi
     * hidup tiap ONU ditarik sekali dalam satu query monitoring, bukan per baris.
     * Pelanggan yang belum tersambung mengembalikan daftar kosong tanpa galat.
     */
    override fun subscriberNeighbors(customerId: UUID): SubscriberNeighbors {
        val customer = customerApi.findCustomer(customerId)
            ?: throw NotFoundException("Pelanggan $customerId tidak ditemukan")
        val placement = customerApi.findPlacementOf(customerId)
            ?: return SubscriberNeighbors(customer.id, null, null, emptyList(), emptyList())

        val upstream = networkApi.upstreamOf(placement.odpId)
        val odpCode = upstream.odp.code

        val sameOdpOccupants = customerApi.findOccupantsOfOdp(placement.odpId)
        // Se-PON: penghuni tiap ODP di bawah port PON yang sama. Bila port PON belum
        // teridentifikasi (jaringan setengah jadi), lingkup jatuh ke ODP saja.
        val ponOdpIds = upstream.ponPort?.let { networkApi.odpIdsUnderPonPort(it.id) }
            ?: setOf(placement.odpId)
        val odpCodeById = networkApi.findOdpsByIds(ponOdpIds).associate { it.id to it.code }
        val samePonPairs = ponOdpIds.flatMap { odpId ->
            val code = odpCodeById[odpId] ?: "?"
            customerApi.findOccupantsOfOdp(odpId).map { code to it }
        }

        // Satu tarikan metrik hidup untuk semua ONU yang muncul (se-ODP ⊆ se-PON).
        val onuIds = HashSet<UUID>()
        sameOdpOccupants.mapTo(onuIds) { it.onuId }
        samePonPairs.mapTo(onuIds) { it.second.onuId }
        val live = monitoringApi.latestMetricsByOnuIds(onuIds)

        fun toNeighbor(code: String, o: OdpOccupant): NeighborView {
            val m = live[o.onuId]
            return NeighborView(
                customerId = o.customerId,
                customerCode = o.customerCode,
                customerName = o.customerName,
                odpCode = code,
                portNumber = o.portNumber,
                onuSerialNumber = o.onuSerialNumber,
                onuStatus = o.onuStatus,
                opticalHealth = o.opticalHealth,
                installRxPowerDbm = o.installRxPowerDbm,
                liveStatus = m?.status,
                liveRxPowerDbm = m?.rxPowerDbm,
                distanceMeters = m?.distanceMeters,
                downCause = m?.downCause,
                self = o.customerId == customerId,
            )
        }

        return SubscriberNeighbors(
            customerId = customer.id,
            odpCode = odpCode,
            ponPortLabel = upstream.ponPort?.name,
            sameOdp = sameOdpOccupants.map { toNeighbor(odpCode, it) }.sortedBy { it.portNumber },
            samePonPort = samePonPairs.map { toNeighbor(it.first, it.second) }
                .sortedWith(compareBy({ it.odpCode }, { it.portNumber })),
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
        if (impacts.isEmpty()) return ImpactedOverlay(emptyList(), emptyList())

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
        // Alarm ber-id-langsung: perangkat (ODP/ODC/OLT) membawa id perangkatnya, dan alarm
        // pelanggan (PPPOE_DOWN dari bng — sesi PPPoE putus) membawa customerId. Seperti impact
        // ONU, id pelanggan itu mewarnai marker pelanggan + kabel drop yang berujung padanya —
        // menutup celah "ONU hidup tapi pelanggan offline" yang tak terlihat dari telemetri optik.
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

        if (nodeSeverity.isEmpty()) return ImpactedOverlay(emptyList(), emptyList())

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
        // Simpul terdampak (OLT/ODC/ODP/pelanggan) untuk diwarnai merah di peta —
        // id-nya sama dengan id fitur tile, jadi frontend tinggal mencocokkan.
        val nodes = nodeSeverity.map { (id, rank) -> ImpactedNode(id = id, severity = severityName(rank)) }
        return ImpactedOverlay(cables, nodes)
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

    /**
     * Menyusun heatmap utilisasi port. Network memberi tiap ODP dalam jangkauan
     * area pengguna (lokasi + kapasitas); customer memberi jumlah port terpakai
     * per ODP dalam SATU query hitung agregat — bukan memuat penghuni tiap ODP,
     * karena satu tenant bisa punya puluhan ribu ODP. Persentase dihitung di sini
     * agar klien tinggal mewarnai titik.
     */
    override fun utilizationHeatmap(): UtilizationHeatmap {
        val areaIds = currentUser.current().areaScope()
        val odps = networkApi.odpsInArea(areaIds)
        if (odps.isEmpty()) return UtilizationHeatmap(emptyList())

        val usedByOdp = customerApi.countOccupantsByOdp(odps.mapTo(HashSet()) { it.id })
        val items = odps.map { odp ->
            val used = (usedByOdp[odp.id] ?: 0L).toInt()
            OdpUtilization(
                odpId = odp.id,
                code = odp.code,
                name = odp.name,
                location = odp.location,
                capacity = odp.capacity,
                used = used,
                utilizationPercent = percentage(used, odp.capacity),
            )
        }
        return UtilizationHeatmap(items)
    }

    /**
     * Menyusun drill-down sebuah PON port. Network memberi topologi PON → ODC → ODP
     * (kapasitas tiap tingkat); customer memberi jumlah port terpakai per ODP dalam
     * SATU query hitung agregat — bukan memuat penghuni tiap ODP, karena satu PON
     * bisa menaungi puluhan ODP. Utilisasi dirol-up dari ODP ke ODC ke PON di sini
     * agar klien tinggal menggambar bar; daftar penghuni tiap ODP diambil terpisah
     * lewat inspectOdp saat operator men-drill ke satu FAT.
     */
    override fun inspectPonPort(ponPortId: UUID): PonPortInspection {
        val topology = networkApi.topologyUnderPonPort(ponPortId)
            ?: throw NotFoundException("PON port $ponPortId tidak ditemukan")

        val allOdpIds = topology.odcs.flatMapTo(HashSet()) { branch -> branch.odps.map { it.id } }
        val usedByOdp = customerApi.countOccupantsByOdp(allOdpIds)

        val branches = topology.odcs.map { branch ->
            val odpItems = branch.odps.map { odp ->
                val used = (usedByOdp[odp.id] ?: 0L).toInt()
                OdpUtilization(
                    odpId = odp.id,
                    code = odp.code,
                    name = odp.name,
                    location = odp.location,
                    capacity = odp.capacity,
                    used = used,
                    utilizationPercent = percentage(used, odp.capacity),
                )
            }
            val capacity = odpItems.sumOf { it.capacity }
            val used = odpItems.sumOf { it.used }
            PonOdcBranch(
                odcId = branch.odc.id,
                code = branch.odc.code,
                name = branch.odc.name,
                energized = branch.odc.energized,
                legCapacity = branch.odc.capacity,
                odpCount = odpItems.size,
                capacity = capacity,
                used = used,
                utilizationPercent = percentage(used, capacity),
                odps = odpItems,
            )
        }

        val capacity = branches.sumOf { it.capacity }
        val used = branches.sumOf { it.used }
        return PonPortInspection(
            ponPortId = topology.ponPortId,
            label = topology.label,
            oltId = topology.oltId,
            odcCount = branches.size,
            odpCount = branches.sumOf { it.odpCount },
            capacity = capacity,
            used = used,
            utilizationPercent = percentage(used, capacity),
            odcs = branches,
        )
    }

    /**
     * Menyusun daftar ONU pelanggan di bawah satu OLT. Network memberi seluruh ODP di
     * hilir OLT (site → OLT → PON → ODC → ODP) lewat primitif [NetworkApi.downstreamDeviceIds];
     * customer memberi penghuni ODP-ODP itu dalam SATU batch (tiga query tetap), jadi total
     * tetap ~query konstan walau OLT menaungi puluhan ODP — bukan N+1. Kode ODP dibawa dari
     * ref-nya supaya tiap baris tahu menggantung di FAT mana. Terurut per kode ODP lalu port.
     */
    override fun listOnusUnderOlt(oltId: UUID): OltOnuList {
        val odpIds = networkApi.downstreamDeviceIds(setOf(oltId), emptySet()).odpIds
        if (odpIds.isEmpty()) return OltOnuList(oltId, 0, emptyList())

        val odpCodeById = networkApi.findOdpsByIds(odpIds).associate { it.id to it.code }
        val rows = customerApi.findOccupantsForOdps(odpIds).entries
            .flatMap { (odpId, occupants) ->
                val odpCode = odpCodeById[odpId] ?: "?"
                occupants.map { it.toOltOnuRow(odpId, odpCode) }
            }
            .sortedWith(compareBy({ it.odpCode }, { it.portNumber }))
        return OltOnuList(oltId, rows.size, rows)
    }

    private fun OdpOccupant.toOltOnuRow(odpId: UUID, odpCode: String) = OltOnuRow(
        onuId = onuId,
        serialNumber = onuSerialNumber,
        customerId = customerId,
        customerCode = customerCode,
        customerName = customerName,
        odpId = odpId,
        odpCode = odpCode,
        portNumber = portNumber,
        onuStatus = onuStatus,
        opticalHealth = opticalHealth,
        installRxPowerDbm = installRxPowerDbm,
        subscriptionPackage = subscriptionPackage,
        subscriptionStatus = subscriptionStatus,
    )

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

    /**
     * Merangkai simpul jalur dari ONT (rumah) ke hulu sampai BRAS. Simpul BRAS
     * ditambahkan di puncak walau hulu fisik kosong — pelanggan bisa saja sudah
     * punya akun PPPoE sebelum ONU-nya terpasang, dan sesi itu tetap layak dilihat.
     */
    private fun buildHops(
        customerLocation: Coordinate,
        upstream: UpstreamPath?,
        bras: SubscriberSessionRef?,
        live: OnuLiveMetric?,
    ): List<TraceHop> = buildList {
        add(TraceHop("CUSTOMER", "", "Rumah pelanggan", customerLocation, detail = customerDetail(live)))
        upstream?.let { up ->
            add(TraceHop("ODP", up.odp.code, up.odp.name, up.odp.location))
            up.odc?.let { add(TraceHop("ODC", it.code, it.name, null)) }
            up.ponPort?.let { add(TraceHop("PON_PORT", it.code, it.name, null)) }
            up.olt?.let { add(TraceHop("OLT", it.code, it.name, null)) }
            up.site?.let { add(TraceHop("SITE", it.code, it.name, null)) }
        }
        bras?.let {
            add(
                TraceHop(
                    kind = "BRAS",
                    code = it.nasName ?: "BRAS",
                    name = it.username,
                    location = null,
                    online = it.online,
                    detail = brasDetail(it),
                ),
            )
        }
    }

    /** Ringkasan bacaan optik hidup untuk badge simpul ONT; null bila belum terbaca. */
    private fun customerDetail(live: OnuLiveMetric?): String? {
        live ?: return null
        return buildString {
            append(live.status)
            live.rxPowerDbm?.let { append(" · Rx ${formatDbm(it)}") }
            live.distanceMeters?.let { append(" · $it m") }
        }
    }

    /** Ringkasan sesi PPPoE untuk badge simpul BRAS. */
    private fun brasDetail(bras: SubscriberSessionRef): String = buildString {
        append(if (bras.online) "Online" else "Offline")
        bras.framedIp?.let { append(" · $it") }
        bras.uptimeSeconds?.takeIf { bras.online }?.let { append(" · uptime ${formatUptime(it)}") }
        bras.rateProfileName?.let { append(" · $it") }
    }

    private fun SubscriberSessionRef.toHopView() = BrasHopView(
        username = username,
        accessStatus = accessStatus,
        rateProfileName = rateProfileName,
        online = online,
        framedIp = framedIp,
        nasName = nasName,
        nasIp = nasIp,
        uptimeSeconds = uptimeSeconds,
    )

    private fun formatDbm(dbm: Double): String = String.format(Locale.US, "%.1f dBm", dbm)

    private fun formatUptime(seconds: Long): String {
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        return when {
            hours > 0 -> "${hours}j ${minutes}m"
            minutes > 0 -> "${minutes}m"
            else -> "${seconds}d"
        }
    }

    private fun percentage(used: Int, total: Int): Int =
        if (total == 0) 0 else (used.toDouble() / total * 100).roundToInt()

    private companion object {
        const val FIBER_LOSS_DB_PER_KM = 0.35
    }
}

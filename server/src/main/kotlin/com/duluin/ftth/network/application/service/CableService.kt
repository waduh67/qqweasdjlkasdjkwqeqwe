package com.duluin.ftth.network.application.service

import com.duluin.ftth.common.domain.Page
import com.duluin.ftth.common.domain.PageRequest
import com.duluin.ftth.common.domain.error.ConflictException
import com.duluin.ftth.common.domain.error.NotFoundException
import com.duluin.ftth.common.domain.UuidV7
import com.duluin.ftth.common.domain.error.ValidationException
import com.duluin.ftth.common.domain.geo.Coordinate
import com.duluin.ftth.common.domain.geo.RoutePath
import com.duluin.ftth.common.infrastructure.audit.AuditRecorder
import com.duluin.ftth.common.security.CurrentUserProvider
import com.duluin.ftth.network.application.port.inbound.AttachCableCommand
import com.duluin.ftth.network.application.port.inbound.CableAttachmentView
import com.duluin.ftth.network.application.port.inbound.CablePortOption
import com.duluin.ftth.network.application.port.inbound.CableView
import com.duluin.ftth.network.application.port.inbound.CableWaypointCommand
import com.duluin.ftth.network.application.port.inbound.DropReleaseView
import com.duluin.ftth.network.application.port.inbound.ManageCableUseCase
import com.duluin.ftth.network.application.port.inbound.ManageFiberConnectionUseCase
import com.duluin.ftth.network.application.port.inbound.ReleaseDropCommand
import com.duluin.ftth.network.application.port.inbound.SaveCableCommand
import com.duluin.ftth.network.application.port.outbound.CableCoreRepository
import com.duluin.ftth.network.application.port.outbound.CableRepository
import com.duluin.ftth.network.application.port.outbound.FiberConnectionRepository
import com.duluin.ftth.network.application.port.outbound.JointBoxRepository
import com.duluin.ftth.network.application.port.outbound.OdcRepository
import com.duluin.ftth.network.application.port.outbound.OdfRepository
import com.duluin.ftth.network.application.port.outbound.OdpRepository
import com.duluin.ftth.network.application.port.outbound.OltRepository
import com.duluin.ftth.network.application.port.outbound.PonPortRepository
import com.duluin.ftth.network.application.port.outbound.SiteRepository
import com.duluin.ftth.network.domain.model.Cable
import com.duluin.ftth.network.domain.model.CableAttachment
import com.duluin.ftth.network.domain.model.CableAttachmentRole
import com.duluin.ftth.network.domain.model.CableCore
import com.duluin.ftth.network.domain.model.CableNaming
import com.duluin.ftth.network.domain.model.CableType
import com.duluin.ftth.network.domain.model.CableWaypoint
import com.duluin.ftth.network.domain.model.ClosureKind
import com.duluin.ftth.network.domain.model.CoreStatus
import com.duluin.ftth.network.domain.model.NetworkEndpoint
import com.duluin.ftth.network.domain.model.NetworkNodeKind
import com.duluin.ftth.network.domain.model.NetworkNodeRef
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
@Transactional
class CableService(
    private val cableRepository: CableRepository,
    private val cableCoreRepository: CableCoreRepository,
    private val siteRepository: SiteRepository,
    private val oltRepository: OltRepository,
    private val odcRepository: OdcRepository,
    private val odpRepository: OdpRepository,
    private val jointBoxRepository: JointBoxRepository,
    private val odfRepository: OdfRepository,
    private val ponPortRepository: PonPortRepository,
    private val fiberConnectionRepository: FiberConnectionRepository,
    private val closures: ClosureLookup,
    private val manageFiberConnection: ManageFiberConnectionUseCase,
    private val currentUser: CurrentUserProvider,
    private val auditor: AuditRecorder,
) : ManageCableUseCase {

    @Transactional(readOnly = true)
    override fun search(query: String, cableType: CableType?, pageRequest: PageRequest): Page<CableView> {
        val page = cableRepository.search(query, cableType, pageRequest)
        val labels = labelsFor(page.content)
        return page.map { it.toView(labels) }
    }

    @Transactional(readOnly = true)
    override fun get(id: UUID): CableView = requireCable(id).toView()

    @Transactional(readOnly = true)
    override fun sourcePorts(kind: NetworkNodeKind, id: UUID): List<CablePortOption> {
        val ref = NetworkNodeRef(kind, id)
        // Kabel yang BERAWAL dari simpul ini menempati port keluarannya.
        val outgoing = cableRepository.findByEndpoint(ref).filter { it.from.ref == ref }
        return when (kind) {
            NetworkNodeKind.OLT -> {
                val byPonPort = outgoing.mapNotNull { c -> c.from.ponPortId?.let { it to c } }.toMap()
                ponPortRepository.findByOltId(id).map { port ->
                    val cable = byPonPort[port.id]
                    CablePortOption(port.id, null, port.label, cable != null, cable?.code)
                }
            }
            // Kabinet & kotak sudah tak menawarkan "port asal" lagi — lihat
            // catatan USANG di [NetworkEndpoint]. Sebuah selubung berangkat dari
            // sana lewat SERATNYA, satu core ke satu kaki splitter, dan pasangan
            // itu dicatat di meja sambung yang menyebut modul & core-nya. Nomor
            // setingkat-kabinet di ujung kabel cuma bisa menyimpan satu dari
            // delapan pasangan yang sebenarnya ada, jadi ia berhenti ditanyakan.
            //
            // Joint box tak punya port keluaran sejak awal: seratnya DISAMBUNG,
            // bukan dicolok. ODF punya port bernomor, tapi yang dicolok di sana
            // patchcord — bukan ujung kabel outdoor; kabel yang berangkat dari rak
            // menempel lewat sambungan di sisi belakang portnya.
            NetworkNodeKind.ODC, NetworkNodeKind.ODP,
            NetworkNodeKind.JOINT_BOX, NetworkNodeKind.ODF,
            NetworkNodeKind.SITE, NetworkNodeKind.CUSTOMER,
            -> emptyList()
        }
    }

    override fun create(command: SaveCableCommand): CableView {
        val from = command.fromEndpoint()
        val to = command.toEndpoint()
        val route = RoutePath(command.route)
        val waypoints = command.waypoints.orEmpty().map { it.toWaypoint() }
        assertNodesExist(from.ref, to.ref, *waypoints.map { it.node }.toTypedArray())
        // Kode yang dikirim operator dipakai apa adanya — termasuk bila ternyata bentrok,
        // sebab menggeser diam-diam kode yang DIKETIK orang berarti label di selubung dan
        // label di layar berbeda. Yang dibuat backend (kolomnya dikosongkan) boleh digeser.
        val code = command.code?.trim()?.uppercase()?.takeIf { it.isNotBlank() }
            ?.also { if (cableRepository.existsByCode(it)) throw ConflictException("Kode kabel '$it' sudah dipakai") }
            ?: autoCode(command.cableType, from, to)
        validateSourcePort(from, excludeCableId = null)
        val cable = cableRepository.save(
            Cable.create(
                tenantId = currentUser.current().tenantId,
                code = code,
                name = command.name,
                cableType = command.cableType,
                coreCount = command.coreCount,
                route = route,
                from = from,
                to = to,
                waypoints = sortAlong(route, waypoints),
                status = command.status,
                installation = command.installation,
                ownership = command.ownership,
            ),
        )
        cableCoreRepository.saveAll(
            CableCore.generate(cable.tenantId, cable.id, from = 1, to = cable.coreCount),
        )
        applyUplink(cable)
        auditor.record(
            "cable.created", "Cable", cable.id, cable.tenantId,
            mapOf("code" to cable.code, "type" to cable.cableType.name, "lengthMeters" to cable.lengthMeters),
        )
        return cable.toView()
    }

    override fun update(id: UUID, command: SaveCableCommand): CableView {
        val cable = requireCable(id)
        val from = command.fromEndpoint()
        val to = command.toEndpoint()
        val route = RoutePath(command.route)
        val waypoints = command.waypoints?.map { it.toWaypoint() }
        assertNodesExist(from.ref, to.ref, *waypoints.orEmpty().map { it.node }.toTypedArray())
        validateSourcePort(from, excludeCableId = id)
        // Ganti kode saat menyunting = merapikan label, bukan memindahkan kabel. Kosong
        // berarti "biarkan" — klien lama yang tak mengenal kolom ini tak boleh menghapus
        // kode yang sudah tertulis di selubung hanya karena ia tak mengirimkannya.
        val code = command.code?.trim()?.uppercase()?.takeIf { it.isNotBlank() } ?: cable.code
        if (code != cable.code && cableRepository.existsByCode(code)) {
            throw ConflictException("Kode kabel '$code' sudah dipakai")
        }
        val previousCoreCount = cable.coreCount
        cable.update(
            code = code,
            name = command.name,
            cableType = command.cableType,
            coreCount = command.coreCount,
            route = route,
            from = from,
            to = to,
            waypoints = waypoints?.let { sortAlong(route, it) },
            status = command.status,
            installation = command.installation,
            ownership = command.ownership,
        )
        val saved = cableRepository.save(cable)
        syncCores(saved, previousCoreCount)
        applyUplink(saved)
        auditor.record("cable.updated", "Cable", saved.id, saved.tenantId, mapOf("code" to saved.code))
        return saved.toView()
    }

    override fun attach(id: UUID, command: AttachCableCommand): CableView {
        val cable = requireCable(id)
        val node = NetworkNodeRef(command.nodeKind, command.nodeId)
        val closure = closures.require(assertClosureKind(command.nodeKind), command.nodeId)
        // "Cuma lewat" pada kotak yang seratnya sudah tersambung adalah dua
        // pernyataan yang saling meniadakan; salah satunya pasti keliru, dan
        // yang punya bukti adalah sambungan yang tercatat.
        val spliced = splicesAt(cable.id, command.nodeId)
        if (command.role == CableAttachmentRole.PASSING && spliced > 0) {
            throw ConflictException(
                "Kabel ${cable.code} punya $spliced sambungan di ${closure.code} — selubungnya jelas " +
                    "sudah dibuka di sana. Lepas dulu sambungannya di meja sambung kalau memang " +
                    "kabel ini cuma melintas.",
            )
        }
        cable.attach(node, command.role, insertionIndex(cable, closure.location))
        val saved = cableRepository.save(cable)
        auditor.record(
            "cable.attached", "Cable", saved.id, saved.tenantId,
            mapOf("code" to saved.code, "node" to closure.code, "role" to command.role.name),
        )
        return saved.toView()
    }

    override fun detach(id: UUID, nodeId: UUID): CableView {
        val cable = requireCable(id)
        val spliced = splicesAt(cable.id, nodeId)
        if (spliced > 0) {
            throw ConflictException(
                "Masih ada $spliced sambungan serat kabel ${cable.code} di dalam kotak itu. " +
                    "Selubung yang mengaku utuh padahal core-nya tersambung persis kebohongan " +
                    "yang bikin orang salah potong — lepas dulu sambungannya di meja sambung.",
            )
        }
        if (!cable.detach(nodeId)) return cable.toView()
        val saved = cableRepository.save(cable)
        auditor.record(
            "cable.detached", "Cable", saved.id, saved.tenantId,
            mapOf("code" to saved.code, "nodeId" to nodeId.toString()),
        )
        return saved.toView()
    }

    /** Berapa serat kabel ini yang tersambung DI DALAM kotak tersebut. */
    private fun splicesAt(cableId: UUID, nodeId: UUID): Int =
        fiberConnectionRepository.findByCableId(cableId).count { it.closureId == nodeId }

    /**
     * Di posisi mana singgahan baru masuk ke barisan.
     *
     * Diukur dari letak kotaknya menyusuri rute: yang berdiri di meter ke-820
     * duduk sesudah kotak di meter ke-300 dan sebelum yang di meter ke-1.200.
     * Inilah satu-satunya tempat geometri masih ikut bicara soal singgahan, dan
     * yang ditentukannya cuma URUTAN — keanggotaannya sudah diputuskan orang yang
     * membuka kotak itu. Kotak yang letaknya tak lagi diketahui dianggap ada di
     * depan, supaya barisan yang sudah benar tak tergeser oleh yang tak diketahui.
     */
    private fun insertionIndex(cable: Cable, location: Coordinate): Int {
        val along = cable.route.distanceAlongTo(location)
        val known = closures.findAll(cable.waypoints.map { it.node.ref })
        return cable.waypoints.count { existing ->
            val point = known[existing.node.id]?.location ?: return@count true
            cable.route.distanceAlongTo(point) <= along
        }
    }

    /**
     * Menyelaraskan barisan core dengan jumlah core kabel setelah diedit.
     *
     * Naik → core baru ditambahkan di ekor; core lama beserta status & catatannya
     * TAK disentuh. Turun → ditolak bila core yang mau dibuang masih terpakai:
     * kabel yang menyusut di data padahal seratnya masih menyalurkan layanan
     * adalah cara paling rapi untuk kehilangan jejak pelanggan. Kalau semuanya
     * bebas, barulah dibuang.
     */
    private fun syncCores(cable: Cable, previousCoreCount: Int) {
        val target = cable.coreCount
        if (target == previousCoreCount) return
        if (target > previousCoreCount) {
            cableCoreRepository.saveAll(
                CableCore.generate(cable.tenantId, cable.id, from = previousCoreCount + 1, to = target),
            )
            return
        }
        val doomed = cableCoreRepository.findByCableId(cable.id)
            .filter { it.coreNumber > target && it.status != CoreStatus.FREE }
        if (doomed.isNotEmpty()) {
            val numbers = doomed.joinToString(", ") { it.coreNumber.toString() }
            throw ConflictException(
                "Jumlah core tak bisa dikurangi jadi $target: core $numbers masih dipakai. " +
                    "Bebaskan dulu di layar Kelola Core.",
            )
        }
        cableCoreRepository.deleteAboveCoreNumber(cable.id, target)
    }

    /**
     * Pencabutan pelanggan dalam satu langkah — lihat [ManageCableUseCase.releaseDrop].
     *
     * Urutannya penting: sambungan dulu (itu yang membebaskan kaki splitter di ODP
     * dan mengembalikan core ke BEBAS), status kabel belakangan. Terbalik pun
     * hasilnya sama, tapi kalau ada yang gagal di tengah, kabel yang terlanjur
     * ditandai ditinggal padahal core-nya masih terpakai jauh lebih membingungkan
     * daripada sebaliknya.
     */
    override fun releaseDrop(id: UUID, command: ReleaseDropCommand): DropReleaseView {
        val cable = requireCable(id)
        if (cable.cableType != CableType.DROP) {
            throw ValidationException(
                "Hanya kabel drop yang bisa dicabut sekaligus. ${cable.code} adalah " +
                    "${cable.cableType.name.lowercase()} yang menyuapi banyak pelanggan — " +
                    "lepas per core di meja sambung kotaknya.",
            )
        }
        val busyBefore = cableCoreRepository.findByCableId(id).count { it.status == CoreStatus.USED }
        val removed = manageFiberConnection.disconnectAllOfCable(id, cableSurvives = true)
        val busyAfter = cableCoreRepository.findByCableId(id).count { it.status == CoreStatus.USED }
        val freed = busyBefore - busyAfter

        if (command.abandon) {
            cable.abandon()
            cableRepository.save(cable)
        }
        auditor.record(
            "cable.drop_released", "Cable", cable.id, cable.tenantId,
            mapOf(
                "code" to cable.code,
                "removedConnections" to removed,
                "freedCores" to freed,
                "status" to cable.status.name,
                "note" to (command.note ?: "-"),
            ),
        )
        return DropReleaseView(
            cableId = cable.id,
            cableCode = cable.code,
            removedConnections = removed,
            freedCores = freed,
            status = cable.status,
            message = releaseMessage(removed, freed, command.abandon),
        )
    }

    /**
     * Kalimat hasil, disusun dari yang benar-benar terjadi — bukan template yang
     * selalu berbunyi sukses. Nol sambungan itu kabar penting: berarti drop ini
     * memang belum pernah didata sambungannya, dan kaki splitter di ODP-nya masih
     * dikira terpakai oleh sesuatu yang lain.
     */
    private fun releaseMessage(removed: Int, freed: Int, abandoned: Boolean): String {
        val inti = if (removed == 0) {
            "Tak ada sambungan tercatat pada drop ini, jadi tak ada yang perlu dilepas."
        } else {
            "$removed sambungan dilepas" + if (freed > 0) ", $freed core kembali bebas." else "."
        }
        val ekor = if (abandoned) {
            " Kabelnya ditandai ditinggal — fisiknya masih di tiang, tapi tak akan " +
                "terhitung sebagai kabel siap pakai."
        } else {
            " Kabelnya dibiarkan apa adanya, siap dipakai penghuni berikutnya."
        }
        return inti + ekor
    }

    override fun delete(id: UUID) {
        val cable = requireCable(id)
        // Sambungan dulu, baru kabelnya: core ikut terhapus bersama kabel, dan
        // sambungan yang menunjuk serat yang tak ada lagi membuat telusur jalur
        // menunjuk jalur yang sudah digulung. Sekalian membebaskan core di sisi
        // seberang yang jadi menganggur.
        manageFiberConnection.disconnectAllOfCable(id)
        cableRepository.deleteById(id)
        releaseUplink(cable)
        auditor.record("cable.deleted", "Cable", id, cable.tenantId, mapOf("code" to cable.code))
    }

    /**
     * Menegakkan aturan port KELUARAN sumber sebuah kabel: portnya ada di simpul,
     * masih di dalam kapasitas, dan belum dipakai kabel lain. Ini yang membuat "gak
     * bisa nambah kabel sembarangan kalau portnya penuh" — okupansi hidup di sini,
     * bukan di unique-index DB, karena rujukan ujung memang tak ber-foreign-key.
     *
     * Port sengaja OPSIONAL di server: kabel tanpa port dibiarkan lewat (kabel lama
     * portnya NULL, dan feeder dari SITE tak mengenal PON port). Kewajiban "pilih
     * port dulu" ditegakkan di UI penarikan kabel — begitu sebuah port dipilih,
     * barulah aturan keberadaan/kapasitas/okupansi di sini berlaku penuh.
     */
    private fun validateSourcePort(from: NetworkEndpoint, excludeCableId: UUID?) {
        when (from.kind) {
            NetworkNodeKind.OLT -> {
                val ponPortId = from.ponPortId ?: return
                val ponPort = ponPortRepository.findById(ponPortId)
                    ?: throw NotFoundException("PON port $ponPortId tidak ditemukan")
                if (ponPort.oltId != from.id) {
                    throw ValidationException("PON port ${ponPort.label} bukan milik OLT sumber")
                }
                conflictingSourceCable(from, excludeCableId) { it.ponPortId == ponPortId }
                    ?.let { throw ConflictException("PON port ${ponPort.label} sudah dipakai kabel ${it.code}") }
            }
            NetworkNodeKind.ODC -> {
                val port = from.portNumber ?: return
                val odc = odcRepository.findById(from.id) ?: throw NotFoundException("ODC ${from.id} tidak ditemukan")
                if (port !in 1..odc.capacity) {
                    throw ValidationException("Kaki splitter $port di luar kapasitas ODC ${odc.code} (1-${odc.capacity})")
                }
                conflictingSourceCable(from, excludeCableId) { it.portNumber == port }
                    ?.let { throw ConflictException("Kaki splitter $port ODC ${odc.code} sudah dipakai kabel ${it.code}") }
            }
            NetworkNodeKind.ODP -> {
                val port = from.portNumber ?: return
                val odp = odpRepository.findById(from.id) ?: throw NotFoundException("ODP ${from.id} tidak ditemukan")
                if (port !in 1..odp.capacity) {
                    throw ValidationException("Slot $port di luar kapasitas ODP ${odp.code} (1-${odp.capacity})")
                }
                conflictingSourceCable(from, excludeCableId) { it.portNumber == port }
                    ?.let { throw ConflictException("Slot $port ODP ${odp.code} sudah dipakai kabel ${it.code}") }
            }
            // SITE feeder tak mengenal PON port; JOINT_BOX tak punya port sama
            // sekali; ODF punya port tapi bukan port KABEL (lihat sourcePorts);
            // CUSTOMER tak pernah jadi sumber kabel.
            NetworkNodeKind.SITE, NetworkNodeKind.JOINT_BOX,
            NetworkNodeKind.ODF, NetworkNodeKind.CUSTOMER,
            -> Unit
        }
    }

    /** Kabel LAIN yang memakai port keluaran sumber yang sama (selain [excludeCableId]). */
    private fun conflictingSourceCable(
        from: NetworkEndpoint,
        excludeCableId: UUID?,
        samePort: (NetworkEndpoint) -> Boolean,
    ): Cable? = cableRepository.findByEndpoint(from.ref)
        .firstOrNull { it.id != excludeCableId && it.from.ref == from.ref && samePort(it.from) }

    /**
     * Fisik = logis: begitu kabel feeder/distribusi tergambar, uplink logis simpul
     * hilir langsung ikut ter-set — operator tak perlu menyetel uplink dua kali.
     * DROP sengaja tak disentuh: pemasangan ONU pelanggan (module customer) yang
     * memegang keterisian slot ODP, dan network tak boleh bergantung padanya.
     */
    private fun applyUplink(cable: Cable) = adjustUplink(cable, connect = true)

    /** Kebalikan [applyUplink]: melepas uplink bila simpul hilir masih menunjuk kabel ini. */
    private fun releaseUplink(cable: Cable) = adjustUplink(cable, connect = false)

    private fun adjustUplink(cable: Cable, connect: Boolean) {
        when (cable.cableType) {
            CableType.FEEDER -> {
                val ponPortId = cable.from.ponPortId ?: return
                if (cable.to.kind != NetworkNodeKind.ODC) return
                val odc = odcRepository.findById(cable.to.id) ?: return
                val target = if (connect) ponPortId else null
                // Saat melepas, hanya kosongkan bila ODC memang masih menunjuk kabel ini.
                if (!connect && odc.ponPortId != ponPortId) return
                if (odc.ponPortId != target) {
                    odc.connectTo(target)
                    odcRepository.save(odc)
                }
            }
            CableType.DISTRIBUTION -> {
                if (cable.to.kind != NetworkNodeKind.ODP) return
                val odcId = resolveUpstreamOdcId(cable.from) ?: return
                val odp = odpRepository.findById(cable.to.id) ?: return
                val target = if (connect) odcId else null
                if (!connect && odp.odcId != odcId) return
                if (odp.odcId != target) {
                    odp.connectTo(target)
                    odpRepository.save(odp)
                }
            }
            // BACKBONE tak pernah menentukan induk siapa pun: ruas antar-POP tak
            // punya simpul hilir, dan ODC ↔ ODC memang dipasang justru supaya ada
            // dua jalan menuju kabinet yang sama. Menyetel uplink dari sini akan
            // membalik induk kabinet setiap kali ring digambar dari arah lain.
            CableType.BACKBONE, CableType.DROP -> Unit
        }
    }

    /** ODC hulu sebuah ujung distribusi: ODC itu sendiri, atau ODC dari ODP yang dirangkai. */
    private fun resolveUpstreamOdcId(from: NetworkEndpoint): UUID? = resolveUpstreamOdcId(from.ref, HashSet())

    /**
     * Penelusuran mundur satu tujuan: menemukan ODC hulu, menembus joint box.
     *
     * JB tak punya identitas logis — ia cuma menyambung serat — jadi ODC hulunya
     * ada di seberang kabel yang MASUK ke sana. Tanpa penelusuran ini, ODP di
     * balik sebuah sambungan haspel akan kehilangan induknya dan hilang dari
     * daftar "ODP di bawah ODC ini". [visited] menjaga rute melingkar (yang di
     * data sangat mungkin salah gambar) tidak berputar selamanya.
     */
    private fun resolveUpstreamOdcId(ref: NetworkNodeRef, visited: MutableSet<UUID>): UUID? {
        if (!visited.add(ref.id)) return null
        return when (ref.kind) {
            NetworkNodeKind.ODC -> ref.id
            NetworkNodeKind.ODP -> odpRepository.findById(ref.id)?.odcId
            NetworkNodeKind.JOINT_BOX -> cableRepository.findByEndpoint(ref)
                .filter { it.to.ref == ref }
                .firstNotNullOfOrNull { resolveUpstreamOdcId(it.from.ref, visited) }
            else -> null
        }
    }

    /**
     * Ujung kabel tidak punya foreign key (bisa menunjuk tabel mana saja), jadi
     * keberadaannya diperiksa di sini. Tanpa ini, salah ketik id menghasilkan
     * kabel yang menggantung ke simpul yang tidak pernah ada — dan baru ketahuan
     * saat telusur jalur gagal berbulan-bulan kemudian.
     *
     * Pelanggan sengaja tidak diperiksa: datanya milik module customer, dan
     * network tidak boleh bergantung padanya. Integritasnya dijaga saat ONU
     * dipasang lewat [com.duluin.ftth.network.NetworkApi].
     */
    private fun assertNodesExist(vararg nodes: NetworkNodeRef) {
        nodes.forEach { node ->
            val exists = when (node.kind) {
                NetworkNodeKind.SITE -> siteRepository.findById(node.id) != null
                NetworkNodeKind.OLT -> oltRepository.findById(node.id) != null
                NetworkNodeKind.ODC -> odcRepository.findById(node.id) != null
                NetworkNodeKind.ODP -> odpRepository.findById(node.id) != null
                NetworkNodeKind.JOINT_BOX -> jointBoxRepository.findById(node.id) != null
                NetworkNodeKind.ODF -> odfRepository.findById(node.id) != null
                NetworkNodeKind.CUSTOMER -> true
            }
            if (!exists) throw NotFoundException("${node.kind} ${node.id} tidak ditemukan")
        }
    }

    /**
     * Kode kabel bawaan, dirakit dari kode kedua ujungnya — lihat [CableNaming] untuk alasan
     * bentuknya. Dulu di sini berdiri UUID mentah: unik, tapi mustahil diucapkan lewat radio
     * dan tak dikenali siapa pun yang membuka meja sambung.
     *
     * Bentrok diselesaikan dengan akhiran angka, bukan ditolak: dua selubung antara sepasang
     * kotak yang sama itu wajar (rute utara & rute selatan), dan menggagalkan penyimpanan
     * kabel yang sudah tergambar cuma karena tabrakan nama buatan sistem itu tak masuk akal.
     */
    private fun autoCode(type: CableType, from: NetworkEndpoint, to: NetworkEndpoint): String {
        val fromCode = nodeCode(from.ref)
        val toCode = nodeCode(to.ref)
        val base = when {
            fromCode != null && toCode != null -> CableNaming.between(type, fromCode, toCode)
            // Drop ke pelanggan: kode pelanggan milik module customer dan tak ditarik ke sini
            // (lihat [assertNodesExist]). Slot ODP asalnya jadi pembeda yang justru lebih
            // berguna di lapangan — "drop dari kotak itu, port tujuh".
            fromCode != null -> CableNaming.anchored(type, fromCode, from.portNumber?.let { "P$it" } ?: tail())
            toCode != null -> CableNaming.anchored(type, toCode, tail())
            else -> CableNaming.anonymous(type, tail())
        }
        if (!cableRepository.existsByCode(base)) return base
        (2..MAX_CODE_VARIANTS).forEach { n ->
            val candidate = CableNaming.withSuffix(base, n)
            if (!cableRepository.existsByCode(candidate)) return candidate
        }
        return CableNaming.anonymous(type, tail())
    }

    /** Potongan pengenal acak, cukup pendek untuk dieja tapi praktis tak berulang. */
    private fun tail(): String = UuidV7.generate().toString().takeLast(6).uppercase()

    private fun nodeCode(ref: NetworkNodeRef): String? = when (ref.kind) {
        NetworkNodeKind.SITE -> siteRepository.findById(ref.id)?.code
        NetworkNodeKind.OLT -> oltRepository.findById(ref.id)?.code
        NetworkNodeKind.ODC -> odcRepository.findById(ref.id)?.code
        NetworkNodeKind.ODP -> odpRepository.findById(ref.id)?.code
        NetworkNodeKind.JOINT_BOX -> jointBoxRepository.findById(ref.id)?.code
        NetworkNodeKind.ODF -> odfRepository.findById(ref.id)?.code
        NetworkNodeKind.CUSTOMER -> null
    }

    private fun requireCable(id: UUID): Cable =
        cableRepository.findById(id) ?: throw NotFoundException("Kabel $id tidak ditemukan")

    /**
     * Kabel cuma bisa singgah di kotak yang bisa DIBUKA teknisi serat. POP, badan
     * OLT, dan rumah pelanggan memang tempat kabel berhenti — tapi berhenti itu
     * peran ujung, bukan singgahan, dan bedanya bukan soal istilah: di ujung
     * seluruh core terbuka, di singgahan cuma sebagian yang diambil.
     */
    private fun assertClosureKind(kind: NetworkNodeKind): ClosureKind = ClosureKind.of(kind)
        ?: throw ValidationException(
            "Kabel cuma bisa singgah di kotak yang bisa dibuka teknisi (ODC, ODP, joint box, ODF). " +
                "$kind bukan salah satunya — kalau kabelnya memang berhenti di sana, jadikan ia ujung kabel.",
        )

    private fun CableWaypointCommand.toWaypoint(): CableWaypoint {
        assertClosureKind(nodeKind)
        return CableWaypoint(NetworkNodeRef(nodeKind, nodeId), role)
    }

    /**
     * Menyusun singgahan mengikuti letak kotaknya sepanjang rute.
     *
     * Urutan tak pernah dipercayakan kepada klien: yang menggambar kabel boleh
     * saja mendaftarkan ODP-7 lebih dulu lalu teringat ODP-3, dan barisan yang
     * salah urut membuat "kotak berikutnya sesudah sini" — pertanyaan yang
     * dipakai teknisi saat menyusuri kabel — menjawab ngawur. Sekali lagi:
     * geometri cuma MENGURUTKAN, keanggotaannya tetap keputusan orang. Kotak
     * yang letaknya tak diketahui ditaruh di belakang tanpa mengubah urutan
     * relatifnya.
     */
    private fun sortAlong(route: RoutePath, waypoints: List<CableWaypoint>): List<CableWaypoint> {
        if (waypoints.size < 2) return waypoints
        val known = closures.findAll(waypoints.map { it.node })
        return waypoints.sortedBy { waypoint ->
            known[waypoint.node.id]?.let { route.distanceAlongTo(it.location) } ?: Double.MAX_VALUE
        }
    }

    /**
     * Sekali lookup untuk SELURUH kabel yang mau ditampilkan, bukan sekali per
     * singgahan: satu halaman daftar kabel gampang menyebut ratusan kotak, dan
     * pola N+1 di sini terasa langsung di layar.
     */
    private fun labelsFor(cables: Collection<Cable>): Map<UUID, ClosureRef> =
        closures.findAll(cables.flatMap { cable -> cable.attachments.map { it.node.ref } })

    private fun Cable.toView(): CableView = toView(labelsFor(listOf(this)))

    private fun Cable.toView(labels: Map<UUID, ClosureRef>) = CableView(
        id = id,
        code = code,
        name = name,
        cableType = cableType,
        coreCount = coreCount,
        route = route,
        lengthMeters = lengthMeters,
        fromKind = from.kind,
        fromId = from.id,
        toKind = to.kind,
        toId = to.id,
        fromPonPortId = from.ponPortId,
        fromPortNumber = from.portNumber,
        toPortNumber = to.portNumber,
        fromPortLabel = resolveFromPortLabel(),
        attachments = attachments.mapIndexed { index, attachment ->
            attachment.toView(index, this, labels[attachment.node.id])
        },
        status = status,
        installation = installation,
        installationLabel = installation?.label,
        ownership = ownership,
        ownershipLabel = ownership.label,
    )

    /**
     * Satu singgahan siap tampil.
     *
     * Jarak kedua UJUNG tak diambil dari letak simpulnya melainkan dari panjang
     * rutenya sendiri — nol dan panjang penuh. Di sanalah selubung habis menurut
     * definisi, sedangkan pin OLT di peta sering berdiri di dalam gedung, puluhan
     * meter dari titik kabelnya digambar.
     */
    private fun CableAttachment.toView(sequence: Int, cable: Cable, closure: ClosureRef?) = CableAttachmentView(
        id = id,
        sequence = sequence,
        nodeKind = node.kind,
        nodeId = node.id,
        nodeCode = closure?.code,
        nodeName = closure?.name,
        role = role,
        roleLabel = role.label,
        spliceable = role.spliceable,
        distanceMeters = when (sequence) {
            0 -> 0.0
            cable.attachments.lastIndex -> cable.route.lengthMeters()
            else -> closure?.let { cable.route.distanceAlongTo(it.location) }
        },
    )

    /**
     * Label port keluaran sumber siap-tampil untuk panel: PON port OLT dilabeli
     * dengan label port-nya (butuh lookup), kaki ODC / slot ODP cukup diturunkan
     * dari nomornya. Null bila kabel tak menyimpan port (legacy / feeder SITE).
     */
    private fun Cable.resolveFromPortLabel(): String? {
        from.ponPortId?.let { id -> return ponPortRepository.findById(id)?.let { "PON ${it.label}" } }
        val port = from.portNumber ?: return null
        return when (from.kind) {
            NetworkNodeKind.ODC -> "Kaki $port"
            NetworkNodeKind.ODP -> "Slot $port"
            else -> "Port $port"
        }
    }

    private companion object {
        /**
         * Sebanyak apa akhiran angka dicoba sebelum menyerah ke pengenal acak. Sepuluh ruas
         * antara sepasang kotak yang sama sudah jauh melewati apa pun yang masuk akal di
         * lapangan; kalau sampai ke sana, penamaannya memang bukan lagi urusan sistem.
         */
        const val MAX_CODE_VARIANTS = 10
    }
}

private fun SaveCableCommand.fromEndpoint() =
    NetworkEndpoint(fromKind, fromId, ponPortId = fromPonPortId, portNumber = fromPortNumber)

private fun SaveCableCommand.toEndpoint() =
    NetworkEndpoint(toKind, toId, portNumber = toPortNumber)

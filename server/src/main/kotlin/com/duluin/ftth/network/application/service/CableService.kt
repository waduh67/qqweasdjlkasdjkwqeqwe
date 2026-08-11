package com.duluin.ftth.network.application.service

import com.duluin.ftth.common.domain.Page
import com.duluin.ftth.common.domain.PageRequest
import com.duluin.ftth.common.domain.error.ConflictException
import com.duluin.ftth.common.domain.error.NotFoundException
import com.duluin.ftth.common.domain.UuidV7
import com.duluin.ftth.common.domain.error.ValidationException
import com.duluin.ftth.common.domain.geo.RoutePath
import com.duluin.ftth.common.infrastructure.audit.AuditRecorder
import com.duluin.ftth.common.security.CurrentUserProvider
import com.duluin.ftth.network.application.port.inbound.CablePortOption
import com.duluin.ftth.network.application.port.inbound.CableView
import com.duluin.ftth.network.application.port.inbound.ManageCableUseCase
import com.duluin.ftth.network.application.port.inbound.ManageFiberConnectionUseCase
import com.duluin.ftth.network.application.port.inbound.SaveCableCommand
import com.duluin.ftth.network.application.port.outbound.CableCoreRepository
import com.duluin.ftth.network.application.port.outbound.CableRepository
import com.duluin.ftth.network.application.port.outbound.JointBoxRepository
import com.duluin.ftth.network.application.port.outbound.OdcRepository
import com.duluin.ftth.network.application.port.outbound.OdfRepository
import com.duluin.ftth.network.application.port.outbound.OdpRepository
import com.duluin.ftth.network.application.port.outbound.OltRepository
import com.duluin.ftth.network.application.port.outbound.PonPortRepository
import com.duluin.ftth.network.application.port.outbound.SiteRepository
import com.duluin.ftth.network.domain.model.Cable
import com.duluin.ftth.network.domain.model.CableCore
import com.duluin.ftth.network.domain.model.CableType
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
    private val manageFiberConnection: ManageFiberConnectionUseCase,
    private val currentUser: CurrentUserProvider,
    private val auditor: AuditRecorder,
) : ManageCableUseCase {

    @Transactional(readOnly = true)
    override fun search(query: String, cableType: CableType?, pageRequest: PageRequest): Page<CableView> =
        cableRepository.search(query, cableType, pageRequest).map { it.toView() }

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
        // Kode kabel dibuat backend: UUIDv7 (terurut waktu) bila frontend tak mengirim
        // kode. UUID praktis tak mungkin tabrakan; existsByCode tetap jadi pengaman.
        val code = command.code?.trim()?.uppercase()?.takeIf { it.isNotBlank() }
            ?: UuidV7.generate().toString().uppercase()
        if (cableRepository.existsByCode(code)) throw ConflictException("Kode kabel '$code' sudah dipakai")
        val from = command.fromEndpoint()
        val to = command.toEndpoint()
        assertNodesExist(from.ref, to.ref)
        validateSourcePort(from, excludeCableId = null)
        val cable = cableRepository.save(
            Cable.create(
                tenantId = currentUser.current().tenantId,
                code = code,
                name = command.name,
                cableType = command.cableType,
                coreCount = command.coreCount,
                route = RoutePath(command.route),
                from = from,
                to = to,
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
        assertNodesExist(from.ref, to.ref)
        validateSourcePort(from, excludeCableId = id)
        val previousCoreCount = cable.coreCount
        cable.update(
            name = command.name,
            cableType = command.cableType,
            coreCount = command.coreCount,
            route = RoutePath(command.route),
            from = from,
            to = to,
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

    private fun requireCable(id: UUID): Cable =
        cableRepository.findById(id) ?: throw NotFoundException("Kabel $id tidak ditemukan")

    private fun Cable.toView() = CableView(
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
        status = status,
        installation = installation,
        installationLabel = installation?.label,
        ownership = ownership,
        ownershipLabel = ownership.label,
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
}

private fun SaveCableCommand.fromEndpoint() =
    NetworkEndpoint(fromKind, fromId, ponPortId = fromPonPortId, portNumber = fromPortNumber)

private fun SaveCableCommand.toEndpoint() =
    NetworkEndpoint(toKind, toId, portNumber = toPortNumber)

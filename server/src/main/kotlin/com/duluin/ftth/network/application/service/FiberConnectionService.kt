package com.duluin.ftth.network.application.service

import com.duluin.ftth.common.domain.error.ConflictException
import com.duluin.ftth.common.domain.error.NotFoundException
import com.duluin.ftth.common.domain.error.ValidationException
import com.duluin.ftth.common.infrastructure.audit.AuditRecorder
import com.duluin.ftth.common.security.CurrentUserProvider
import com.duluin.ftth.iam.IamApi
import com.duluin.ftth.network.SpliceWorkOrderPort
import com.duluin.ftth.network.application.port.inbound.ClosureSpliceView
import com.duluin.ftth.network.application.port.inbound.ConnectFiberCommand
import com.duluin.ftth.network.application.port.inbound.ConnectionPointCommand
import com.duluin.ftth.network.application.port.inbound.CoreMoveView
import com.duluin.ftth.network.application.port.inbound.FiberConnectionPointView
import com.duluin.ftth.network.application.port.inbound.FiberConnectionView
import com.duluin.ftth.network.application.port.inbound.ManageFiberConnectionUseCase
import com.duluin.ftth.network.application.port.inbound.MoveCoreCommand
import com.duluin.ftth.network.application.port.inbound.SpliceCableView
import com.duluin.ftth.network.application.port.inbound.SpliceCoreView
import com.duluin.ftth.network.application.port.inbound.SplicePointView
import com.duluin.ftth.network.application.port.inbound.SpliceWorkbenchView
import com.duluin.ftth.network.application.port.inbound.UpdateFiberConnectionCommand
import com.duluin.ftth.network.application.port.outbound.CableCoreRepository
import com.duluin.ftth.network.application.port.outbound.CableRepository
import com.duluin.ftth.network.application.port.outbound.FiberConnectionRepository
import com.duluin.ftth.network.application.port.outbound.OltRepository
import com.duluin.ftth.network.application.port.outbound.PonPortRepository
import com.duluin.ftth.network.application.port.outbound.SplitterRepository
import com.duluin.ftth.network.domain.model.Cable
import com.duluin.ftth.network.domain.model.CableAttachmentRole
import com.duluin.ftth.network.domain.model.CableCore
import com.duluin.ftth.network.domain.model.ClosureKind
import com.duluin.ftth.network.domain.model.ConnectionPoint
import com.duluin.ftth.network.domain.model.ConnectionPointKind
import com.duluin.ftth.network.domain.model.CoreStatus
import com.duluin.ftth.network.domain.model.FiberConnection
import com.duluin.ftth.network.domain.model.OdfPortSide
import com.duluin.ftth.network.domain.model.PonPort
import com.duluin.ftth.network.domain.model.Splitter
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Sambung & putus serat di dalam closure.
 *
 * Di sinilah janji utama desain ulang ini ditepati: yang tersambung adalah CORE,
 * bukan kabel. Karena itu semua aturan di berkas ini berputar pada satu
 * pertanyaan — "apakah serat ini benar-benar ada di kotak yang sedang dibuka?" —
 * dan bukan pada bentuk topologi kabel A→B yang lama.
 */
@Service
@Transactional
class FiberConnectionService(
    private val connections: FiberConnectionRepository,
    private val cableRepository: CableRepository,
    private val cableCoreRepository: CableCoreRepository,
    private val closures: ClosureLookup,
    private val oltRepository: OltRepository,
    private val ponPortRepository: PonPortRepository,
    private val splitters: SplitterRepository,
    private val currentUser: CurrentUserProvider,
    private val auditor: AuditRecorder,
    private val workOrders: SpliceWorkOrderPort,
    private val iam: IamApi,
) : ManageFiberConnectionUseCase {

    @Transactional(readOnly = true)
    override fun list(closureKind: ClosureKind, closureId: UUID): ClosureSpliceView {
        val closure = requireClosure(closureKind, closureId)
        val rows = connections.findByClosureId(closureId)
        return ClosureSpliceView(
            closureKind = closure.kind,
            closureId = closure.id,
            closureCode = closure.code,
            closureName = closure.name,
            connections = rows.toViews(),
        )
    }

    /**
     * Pekerjaan serat sebuah work order, dikelompokkan per kotak yang dibuka.
     *
     * Kotak yang sudah dihapus setelah pekerjaannya dicatat tetap muncul —
     * dengan kode apa adanya dari jenisnya — sebab menyembunyikan barisnya
     * membuat tiket terbaca seolah lebih sedikit dikerjakan daripada
     * sesungguhnya.
     */
    @Transactional(readOnly = true)
    override fun byWorkOrder(workOrderId: UUID): List<ClosureSpliceView> {
        val rows = connections.findByWorkOrderId(workOrderId)
        if (rows.isEmpty()) return emptyList()
        val views = rows.toViews().associateBy { it.id }
        return rows.groupBy { it.closureKind to it.closureId }
            .map { (key, group) ->
                val (kind, id) = key
                val closure = closures.find(kind, id)
                ClosureSpliceView(
                    closureKind = kind,
                    closureId = id,
                    closureCode = closure?.code ?: kind.label,
                    closureName = closure?.name ?: "Kotak sudah dihapus",
                    connections = group.mapNotNull { views[it.id] },
                )
            }
            .sortedBy { it.closureCode }
    }

    /**
     * Meja kerja splicing sebuah kotak, dirakit dalam sekali jalan.
     *
     * Isinya persis kabel yang TERCATAT menyinggahi kotak ini — tak lebih, tak
     * kurang. Urutannya sengaja: yang BERUJUNG di sini duluan, lalu yang dikupas
     * di tengah menurut letak kupasannya, dan paling bawah yang selubungnya utuh.
     * Di depan kotak yang terbuka, kabel yang berakhir di situ adalah yang paling
     * sering dicari; yang cuma lewat hampir tak pernah dikerjakan tapi tetap harus
     * terlihat, supaya tak ada selubung asing yang tak dikenali di dalam kotak.
     */
    @Transactional(readOnly = true)
    override fun workbench(closureKind: ClosureKind, closureId: UUID): SpliceWorkbenchView {
        val closure = requireClosure(closureKind, closureId)
        val rows = connections.findByClosureId(closureId)
        val cables = attachedCables(closure)
        return SpliceWorkbenchView(
            closureKind = closure.kind,
            closureId = closure.id,
            closureCode = closure.code,
            closureName = closure.name,
            spliceCapacity = closure.spliceCapacity,
            spliceCount = rows.size,
            cables = cables.toCableViews(closure),
            points = pointsOf(closure),
            connections = rows.toViews(),
        )
    }

    override fun connect(command: ConnectFiberCommand): FiberConnectionView {
        val closure = requireClosure(command.closureKind, command.closureId)
        val a = command.a.toPoint()
        val b = command.b.toPoint()
        assertPairMakesSense(a, b)
        val cores = listOf(a, b).mapNotNull { point -> validate(point, closure) }
        assertNotSameCable(cores)
        assertRoomLeft(closure)

        val actor = currentUser.current()
        val workOrderId = command.workOrderId?.let { requireWorkOrder(it).id }
        val saved = connections.save(
            FiberConnection.create(
                tenantId = actor.tenantId,
                closureKind = closure.kind,
                closureId = closure.id,
                a = a,
                b = b,
                method = command.method,
                lossDb = command.lossDb,
                note = command.note,
                workOrderId = workOrderId,
                splicedBy = actor.userId,
            ),
        )
        occupy(cores)
        auditor.record(
            "fiber.connected", closure.kind.name, closure.id, saved.tenantId,
            mapOf(
                "closure" to closure.code,
                "a" to a.description,
                "b" to b.description,
                "workOrder" to (workOrderId?.toString() ?: "-"),
            ),
        )
        noteToWorkOrder(saved, "Serat disambung di ${closure.code}: ${a.description} ↔ ${b.description}")
        return listOf(saved).toViews().first()
    }

    /**
     * Semua atau tak sama sekali — dijamin oleh transaksi milik method ini:
     * penolakan pasangan ke-berapa pun melempar keluar, dan yang terlanjur masuk
     * ikut tergulung balik. Pemeriksaan "titik sudah dipakai" pun tetap sahih di
     * dalam satu batch, sebab query berikutnya memaksa Hibernate menyiram
     * sambungan yang baru ditulis lebih dulu.
     */
    override fun connectAll(commands: List<ConnectFiberCommand>): List<FiberConnectionView> {
        if (commands.isEmpty()) throw ValidationException("Tak ada pasangan yang disambung")
        return commands.map { connect(it) }
    }

    override fun update(id: UUID, command: UpdateFiberConnectionCommand): FiberConnectionView {
        val connection = requireConnection(id)
        val attaching = command.workOrderId != null && command.workOrderId != connection.workOrderId
        command.workOrderId?.let { connection.attachWorkOrder(requireWorkOrder(it).id) }
        connection.update(command.method, command.lossDb, command.note)
        val saved = connections.save(connection)
        auditor.record(
            "fiber.connection.updated", saved.closureKind.name, saved.closureId, saved.tenantId,
            mapOf("lossDb" to (saved.lossDb ?: "-"), "method" to saved.method.name),
        )
        // Hanya saat WO-nya baru menempel: hasil ukur redaman yang menyusul tiap
        // hari tak perlu memenuhi linimasa tiket dengan baris yang sama berulang.
        if (attaching) {
            val closure = closures.find(saved.closureKind, saved.closureId)
            noteToWorkOrder(
                saved,
                "Sambungan di ${closure?.code ?: saved.closureKind.label} dibukukan ke work order ini: " +
                    "${saved.a.description} ↔ ${saved.b.description}",
            )
        }
        return listOf(saved).toViews().first()
    }

    /**
     * Pindah serat satu langkah: semua sambungan helai lama diangkat ke helai
     * cadangan, di semua kotak yang disinggahinya, dalam satu transaksi.
     *
     * Urutannya penting — seluruh penolakan diselesaikan SEBELUM baris pertama
     * disentuh, sebab pemindahan yang gagal di tengah meninggalkan serat yang
     * separuh di sini separuh di sana: keadaan yang di lapangan berarti pelanggan
     * mati dan tak seorang pun tahu kenapa.
     */
    override fun moveCore(command: MoveCoreCommand): CoreMoveView {
        val from = requireCore(command.fromCoreId)
        val to = requireCore(command.toCoreId)
        if (from.id == to.id) throw ValidationException("Core asal dan tujuan sama — tak ada yang dipindah")
        val cable = cableRepository.findById(from.cableId)
            ?: throw NotFoundException("Kabel core ${from.coreNumber} tidak ditemukan")
        // Selubung yang sama, sebab yang dijanjikan langkah ini adalah "ganti
        // helai, jalur tetap". Helai di kabel lain menempuh rute lain dan berujung
        // di kotak lain — itu pembangunan jalur baru, bukan pemindahan.
        if (to.cableId != from.cableId) {
            throw ValidationException(
                "Core tujuan harus sehelai di kabel yang sama (${cable.code}) — " +
                    "pindah ke kabel lain berarti membangun jalur baru, bukan mengganti serat",
            )
        }
        if (to.status != CoreStatus.FREE) {
            throw ConflictException(
                "Core ${to.coreNumber} kabel ${cable.code} berstatus ${to.status.label} — pilih helai yang bebas",
            )
        }

        val touching = connections.findByCoreIds(listOf(from.id, to.id))
        if (touching.any { to.id in it.coreIds }) {
            throw ConflictException("Core ${to.coreNumber} kabel ${cable.code} ternyata masih dipakai sambungan lain")
        }
        val moving = touching.filter { from.id in it.coreIds }
        if (moving.isEmpty()) {
            throw ConflictException(
                "Core ${from.coreNumber} kabel ${cable.code} belum menyalurkan apa-apa — " +
                    "cukup ubah statusnya, tak ada yang perlu dipindah",
            )
        }
        val workOrder = command.workOrderId?.let { requireWorkOrder(it) }

        moving.forEach { connection ->
            connection.moveCore(from.id, to.id)
            // Tiket cuma ditempelkan ke yang belum punya: sambungan lama yang lahir
            // dari WO pembangunan tetap menunjuk WO itu — di situlah jawabannya kalau
            // suatu hari ditanya "jalur ini dulu dipasang dalam rangka apa".
            if (workOrder != null && connection.workOrderId == null) connection.attachWorkOrder(workOrder.id)
        }
        val saved = moving.map { connections.save(it) }
        to.update(CoreStatus.USED, to.note)
        from.update(
            if (command.markSourceDamaged) CoreStatus.DAMAGED else CoreStatus.FREE,
            command.reason ?: from.note,
        )
        cableCoreRepository.saveAll(listOf(from, to))

        auditor.record(
            "fiber.core.moved", "CABLE", cable.id, from.tenantId,
            mapOf(
                "cable" to cable.code,
                "from" to from.coreNumber,
                "to" to to.coreNumber,
                "connections" to saved.size,
                "sourceDamaged" to command.markSourceDamaged,
                "reason" to (command.reason ?: "-"),
            ),
        )
        workOrder?.let {
            workOrders.noteSpliceActivity(
                it.id,
                "Serat kabel ${cable.code} dipindah: core ${from.coreNumber} → core ${to.coreNumber}, " +
                    "${saved.size} sambungan ikut" + (command.reason?.let { why -> " ($why)" } ?: ""),
                currentUser.currentOrNull()?.userId,
            )
        }
        return CoreMoveView(
            cableId = cable.id,
            cableCode = cable.code,
            fromCore = from.toView(),
            toCore = to.toView(),
            movedConnections = saved.toViews(),
        )
    }

    override fun disconnect(id: UUID) {
        val connection = requireConnection(id)
        connections.deleteAll(listOf(connection))
        release(connection.coreIds)
        auditor.record(
            "fiber.disconnected", connection.closureKind.name, connection.closureId, connection.tenantId,
            mapOf("a" to connection.a.description, "b" to connection.b.description),
        )
        // Barisnya hilang dari tabel, tapi WO-nya harus tetap ingat pernah ada
        // sambungan ini — kalau tidak, tiket yang seratnya dilepas kembali
        // terbaca seolah tak pernah disentuh.
        val closure = closures.find(connection.closureKind, connection.closureId)
        noteToWorkOrder(
            connection,
            "Sambungan di ${closure?.code ?: connection.closureKind.label} dilepas: " +
                "${connection.a.description} ↔ ${connection.b.description}",
        )
    }

    override fun disconnectAllOfCable(cableId: UUID, cableSurvives: Boolean): Int {
        val doomed = connections.findByCableId(cableId)
        if (doomed.isEmpty()) return 0
        connections.deleteAll(doomed)
        // Core sisi seberang (milik kabel LAIN) selalu ikut dibebaskan. Core kabel
        // ini sendiri hanya dirapikan bila kabelnya tetap ada — kalau ia sedang
        // dihapus, core-nya lenyap bersamanya.
        val own = cableCoreRepository.findByCableId(cableId).map { it.id }.toSet()
        val touched = doomed.flatMap { it.coreIds }
        release(if (cableSurvives) touched else touched.filterNot { it in own })
        return doomed.size
    }

    // ------------------------------------------------------------------
    // Aturan
    // ------------------------------------------------------------------

    /**
     * Memeriksa satu ujung, mengembalikan core yang dipakainya (null untuk titik
     * non-core). Dua hal yang dijaga: titiknya masuk akal untuk closure ini, dan
     * titiknya belum dipakai sambungan lain.
     */
    private fun validate(point: ConnectionPoint, closure: ClosureRef): CableCore? = when (point.kind) {
        ConnectionPointKind.CORE -> validateCore(point, closure)

        ConnectionPointKind.SPLITTER_IN -> {
            requireSplitterOf(point, closure)
            assertFree(point, closure)
            null
        }

        ConnectionPointKind.SPLITTER_OUT -> {
            val splitter = requireSplitterOf(point, closure)
            val leg = point.portNumber ?: 0
            if (!splitter.hasLeg(leg)) {
                throw ValidationException(
                    "Kaki $leg di luar splitter ${splitter.code} ${closure.code} " +
                        "yang rasionya ${splitter.ratio.label} (1-${splitter.legCount})",
                )
            }
            assertFree(point, closure)
            null
        }

        ConnectionPointKind.ODF_PORT -> {
            requireRack(closure, "Port ODF")
            // Port yang ditunjuk TAK harus milik rak closure-nya: sehelai
            // patchcord memang bisa membentang antara dua rak dalam satu POP.
            // Yang dicatat closure adalah di kotak mana pekerjaan itu dilakukan;
            // yang menjaga port tak dobel-pakai adalah [assertFree], dan itu
            // berlaku global.
            val odfId = requireNotNull(point.nodeId)
            val odf = closures.require(ClosureKind.ODF, odfId)
            val portCount = odf.portCount ?: 0
            val port = point.portNumber ?: 0
            if (port !in 1..portCount) {
                throw ValidationException("Port $port di luar kapasitas ODF ${odf.code} (1-$portCount)")
            }
            assertFree(point, closure)
            null
        }

        ConnectionPointKind.PON_PORT -> {
            requireRack(closure, "PON port")
            val ponPortId = requireNotNull(point.nodeId)
            ponPortRepository.findById(ponPortId) ?: throw NotFoundException("PON port $ponPortId tidak ditemukan")
            assertFree(point, closure)
            null
        }

        ConnectionPointKind.ONU ->
            throw ValidationException("Ujung ONU tercatat lewat pemasangan ONU pelanggan, bukan di layar sambungan")
    }

    private fun validateCore(point: ConnectionPoint, closure: ClosureRef): CableCore {
        val coreId = requireNotNull(point.coreId)
        val core = cableCoreRepository.findById(coreId) ?: throw NotFoundException("Core $coreId tidak ditemukan")
        val cable = cableRepository.findById(core.cableId)
            ?: throw NotFoundException("Kabel core ${core.coreNumber} tidak ditemukan")
        if (core.status == CoreStatus.DAMAGED) {
            throw ConflictException("Core ${core.coreNumber} kabel ${cable.code} ditandai rusak — perbaiki dulu")
        }
        assertSheathOpenHere(cable, closure)
        connections.findByCoreInClosure(closure.id, coreId)?.let {
            throw ConflictException(
                "Core ${core.coreNumber} kabel ${cable.code} sudah disambung di ${closure.code}",
            )
        }
        return core
    }

    /**
     * Selubung kabel harus benar-benar TERBUKA di kotak ini.
     *
     * Dulu ini diuji dari geometri: rute yang lewat dalam radius tertentu dianggap
     * "sampai". Ukuran itu salah asas — jarak bukan topologi. Kabel yang kebetulan
     * melintas di depan kotak tetap lolos meski selubungnya tak pernah disentuh,
     * sedangkan kabel yang benar-benar dikupas di sana bisa ditolak hanya karena
     * rutenya digambar kasar. Yang menentukan boleh-tidaknya menyambung adalah
     * PERBUATAN yang tercatat: singgahan END (ujung, seluruh core keluar) atau
     * TAPPED (dikupas di tengah bentang).
     *
     * PASSING ditolak dengan sengaja, dan pesannya menyebutkan jalan keluarnya:
     * kabel yang selubungnya utuh memang ada di dalam kotak — kelihatan, boleh
     * dipegang — tapi menyambungkan core-nya berarti kupasan yang belum pernah
     * dikerjakan. Kalau memang baru saja dikupas, itu yang dicatat lebih dulu.
     */
    private fun assertSheathOpenHere(cable: Cable, closure: ClosureRef) {
        val attachment = cable.attachmentAt(closure.id) ?: throw ValidationException(
            "Kabel ${cable.code} tak tercatat menyinggahi ${closure.code}. " +
                "Kalau selubungnya memang dibuka di sini, catat dulu singgahannya — " +
                "sesudah itu core-nya bisa disambung.",
        )
        if (!attachment.role.spliceable) {
            throw ValidationException(
                "Kabel ${cable.code} tercatat cuma lewat ${closure.code} dengan selubung utuh — " +
                    "tak ada core yang terbuka di sana. Kalau selubungnya baru saja dibuka, " +
                    "ubah dulu singgahannya jadi \"${CableAttachmentRole.TAPPED.label}\".",
            )
        }
    }

    /**
     * Pasangan yang benar-benar punya bentuk fisik di dalam rak.
     *
     * Sisi BELAKANG cuma bertemu core kabel luar — di situlah pigtail dilas, dan
     * setelah itu tak disentuh bertahun-tahun. Sisi DEPAN cuma menerima
     * patchcord: ke PON port OLT, atau ke sisi depan port lain saat sebuah POP
     * hanya DILEWATI feeder (masuk di satu port, keluar di port lain, tanpa
     * menyentuh OLT). PON port pun tak punya pasangan lain: kabel outdoor tak
     * pernah dicolok langsung ke badan OLT.
     *
     * Ini bukan kelonggaran yang belum sempat ditulis — menyambung core langsung
     * ke sisi depan berarti mengaku ada konektor di ujung kabel luar, dan jalur
     * yang ditelusuri dari data itu tak akan pernah cocok dengan raknya.
     */
    private fun assertPairMakesSense(a: ConnectionPoint, b: ConnectionPoint) {
        listOf(a to b, b to a).forEach { (point, other) ->
            when (point.kind) {
                ConnectionPointKind.ODF_PORT -> when (point.portSide) {
                    OdfPortSide.BACK -> if (other.kind != ConnectionPointKind.CORE) {
                        throw ValidationException(
                            "${point.description} hanya disambung ke core kabel — " +
                                "di sisi belakang itulah pigtail dilas, bukan ${other.description}",
                        )
                    }
                    OdfPortSide.FRONT -> {
                        val patchable = other.kind == ConnectionPointKind.PON_PORT ||
                            (other.kind == ConnectionPointKind.ODF_PORT && other.portSide == OdfPortSide.FRONT)
                        if (!patchable) {
                            throw ValidationException(
                                "${point.description} hanya menerima patchcord: ke PON port OLT, " +
                                    "atau ke sisi depan port ODF lain bila POP ini cuma dilewati",
                            )
                        }
                    }
                    null -> Unit // Tak mungkin: ConnectionPoint mewajibkan sisi untuk port ODF.
                }

                ConnectionPointKind.PON_PORT ->
                    if (other.kind != ConnectionPointKind.ODF_PORT || other.portSide != OdfPortSide.FRONT) {
                        throw ValidationException(
                            "PON port disambung lewat patchcord dari sisi depan port ODF, bukan ${other.description}",
                        )
                    }

                else -> Unit
            }
        }
    }

    /**
     * Dua core sehelai kabel yang sama tak boleh dikawinkan.
     *
     * Secara fisik alat las tak keberatan — makanya dulu lolos — tapi hasilnya
     * serat yang berbalik arah: cahaya berangkat dari ODC lewat core 1, menempuh
     * sekian ratus meter sampai kotak ini, lalu pulang ke ODC lagi lewat core 2.
     * Dua core habis, tak ada satu pun pelanggan yang terlayani, dan penelusuran
     * jalur berputar-putar di tempat yang sama.
     *
     * Yang dikerjakan joint box justru kebalikannya: menyatukan core kabel yang
     * DATANG dengan core kabel LANJUTAN. Kalau kabel lanjutannya belum ditarik,
     * memang belum ada yang bisa disambung di sini — bukan kabelnya yang
     * disambung ke dirinya sendiri.
     *
     * Loop sungguhan memang ada di lapangan (mengukur sepasang serat dari satu
     * ujung dengan OTDR), tapi itu pekerjaan sementara yang dibongkar lagi begitu
     * selesai — bukan bentuk jaringan yang layak dicatat sebagai terpasang.
     *
     * Ukuran daftar sudah cukup jadi penanda: [validate] hanya mengembalikan core
     * untuk titik ber-serat, jadi dua isi berarti kedua ujungnya memang core.
     */
    private fun assertNotSameCable(cores: List<CableCore>) {
        if (cores.size < 2) return
        val (from, to) = cores
        if (from.cableId != to.cableId) return
        val cable = cableRepository.findById(from.cableId)
        throw ValidationException(
            "Core ${from.coreNumber} dan core ${to.coreNumber} sehelai di kabel yang sama " +
                "(${cable?.code ?: "?"}) — menyambungnya membuat cahaya berbalik ke tempat asalnya " +
                "dan menghabiskan dua core tanpa melayani siapa pun. Sambungkan core kabel yang datang " +
                "ke core kabel lanjutan; bila kabel lanjutannya belum ada, tarik dulu kabelnya dari kotak ini.",
        )
    }

    /** Port ODF & PON port cuma ada artinya di dalam rak; di kotak lain tak ada raknya. */
    private fun requireRack(closure: ClosureRef, what: String) {
        if (closure.kind != ClosureKind.ODF) {
            throw ValidationException(
                "$what disambung di dalam rak ODF — ${closure.code} adalah ${closure.kind.label}",
            )
        }
    }

    /**
     * Modul splitter yang benar-benar ada DI DALAM kotak yang sedang dibuka.
     *
     * Sebuah kabinet bisa berisi beberapa modul dengan rasio berbeda, jadi titik
     * sambungnya menunjuk splitter — bukan kabinetnya. Dua penolakan di sini:
     * kotak yang memang tak pernah berisi splitter (joint box, ODF: isinya cuma
     * tray dan sambungan serat ke serat), dan modul milik kabinet sebelah yang
     * ikut terbawa karena salah pilih di layar.
     */
    private fun requireSplitterOf(point: ConnectionPoint, closure: ClosureRef): Splitter {
        if (!closure.kind.hasSplitter) {
            throw ValidationException(
                "${closure.code} adalah ${closure.kind.label} yang tak berisi splitter — " +
                    "di dalamnya serat disambung langsung ke serat",
            )
        }
        val splitterId = requireNotNull(point.nodeId)
        val splitter = splitters.findById(splitterId)
            ?: throw NotFoundException("Splitter $splitterId tidak ditemukan")
        if (splitter.ownerId != closure.id) {
            throw ValidationException("Splitter ${splitter.code} bukan isi ${closure.code}")
        }
        return splitter
    }

    private fun assertFree(point: ConnectionPoint, closure: ClosureRef) {
        connections.findByNodePoint(
            point.kind,
            requireNotNull(point.nodeId),
            point.portNumber,
            point.portSide,
        )?.let {
            throw ConflictException("${point.description} di ${closure.code} sudah dipakai sambungan lain")
        }
    }

    /** Core yang baru tersambung jadi TERPAKAI — termasuk yang tadinya dicadangkan. */
    private fun occupy(cores: List<CableCore>) {
        val changed = cores.filter { it.status != CoreStatus.USED }
        if (changed.isEmpty()) return
        changed.forEach { it.update(CoreStatus.USED, it.note) }
        cableCoreRepository.saveAll(changed)
    }

    /**
     * Mengembalikan core ke BEBAS setelah sambungan terakhirnya lepas. Core yang
     * masih punya sambungan di closure lain tetap terpakai — sehelai serat baru
     * benar-benar bebas kalau kedua ujungnya lepas. Core rusak dibiarkan rusak:
     * memutus sambungan tidak menyambung seratnya kembali.
     */
    private fun release(coreIds: List<UUID>) {
        if (coreIds.isEmpty()) return
        val stillUsed = connections.findByCoreIds(coreIds).flatMap { it.coreIds }.toSet()
        val freed = cableCoreRepository.findByIds(coreIds.distinct() - stillUsed)
            .filter { it.status == CoreStatus.USED }
        if (freed.isEmpty()) return
        freed.forEach { it.update(CoreStatus.FREE, it.note) }
        cableCoreRepository.saveAll(freed)
    }

    // ------------------------------------------------------------------
    // Pemuatan & pemetaan
    // ------------------------------------------------------------------

    private fun requireClosure(kind: ClosureKind, id: UUID): ClosureRef = closures.require(kind, id)

    // ------------------------------------------------------------------
    // Meja kerja
    // ------------------------------------------------------------------

    /**
     * Kabel yang ada di dalam kotak ini — SEMUA yang tercatat menyinggahinya,
     * termasuk yang selubungnya utuh.
     *
     * Yang cuma lewat sengaja ikut ditampilkan meski core-nya tak bisa disambung.
     * Justru itu gunanya: teknisi yang membuka kotak menemukan selubung asing di
     * dalamnya, dan daftar yang diam soal kabel itu membuatnya menebak — tebakan
     * yang berakhir dengan kabel orang lain terpotong. Yang menahan tangannya
     * bukan penyembunyian, melainkan baris yang jujur berkata "ini cuma lewat".
     */
    private fun attachedCables(closure: ClosureRef): List<Cable> = cableRepository.findAttachedTo(closure.id)

    private fun List<Cable>.toCableViews(closure: ClosureRef): List<SpliceCableView> {
        if (isEmpty()) return emptyList()
        val coresByCable = cableCoreRepository.findByCableIds(map { it.id }).groupBy { it.cableId }
        // Satu query untuk seluruh core semua kabel: yang dicari adalah "core ini
        // sudah dilas di mana", dan jawabannya bisa berada di kotak mana pun.
        val touching = connections.findByCoreIds(coresByCable.values.flatten().map { it.id })
        val here = HashMap<UUID, UUID>()
        val elsewhere = HashSet<UUID>()
        touching.forEach { connection ->
            connection.coreIds.forEach { coreId ->
                if (connection.closureId == closure.id) here[coreId] = connection.id else elsewhere += coreId
            }
        }
        return mapNotNull { cable ->
            // Kabel tanpa singgahan di sini tak mungkin muncul lewat findAttachedTo;
            // penjagaan ini cuma menutup kemungkinan pemanggil lain menyodorkannya.
            val role = cable.attachmentAt(closure.id)?.role ?: return@mapNotNull null
            SpliceCableView(
                cableId = cable.id,
                code = cable.code,
                name = cable.name,
                cableType = cable.cableType,
                coreCount = cable.coreCount,
                lengthMeters = cable.lengthMeters,
                role = role,
                roleLabel = role.label,
                spliceable = role.spliceable,
                terminatesHere = role == CableAttachmentRole.END,
                tapDistanceMeters = cable.route.distanceAlongTo(closure.location),
                cores = coresByCable[cable.id].orEmpty().map { core ->
                    SpliceCoreView(
                        core = core.toView(),
                        connectionId = here[core.id],
                        connectedElsewhere = core.id in elsewhere,
                    )
                },
            )
        }.sortedWith(
            // Yang bisa dikerjakan lebih dulu, baru yang cuma lewat: di depan kotak
            // yang terbuka, kabel berujung di sinilah yang paling sering dicari,
            // dan selubung utuh justru paling jarang disentuh.
            compareByDescending<SpliceCableView> { it.terminatesHere }
                .thenByDescending { it.spliceable }
                .thenBy { it.tapDistanceMeters },
        )
    }

    /**
     * Titik non-core yang tersedia di kotak ini — dan hanya yang memang ada
     * bentuk fisiknya. Joint box tak menghasilkan satu pun: di dalamnya serat
     * cuma bertemu serat, dan menawarkan "kaki splitter" di sana akan menyesatkan
     * orang yang berdiri di depan kotaknya.
     */
    private fun pointsOf(closure: ClosureRef): List<SplicePointView> = when (closure.kind) {
        ClosureKind.ODC, ClosureKind.ODP -> splitterPoints(closure)
        ClosureKind.ODF -> odfPoints(closure) + ponPoints(closure)
        ClosureKind.JOINT_BOX -> emptyList()
    }

    private fun splitterPoints(closure: ClosureRef): List<SplicePointView> {
        val modules = splitters.findByOwnerId(closure.id)
        if (modules.isEmpty()) return emptyList()
        val ids = modules.mapTo(HashSet()) { it.id }
        val inputs = occupancyOf(ConnectionPointKind.SPLITTER_IN, ids)
        val legs = occupancyOf(ConnectionPointKind.SPLITTER_OUT, ids)
        return modules.flatMap { module ->
            val group = "${module.code} · ${module.ratio.label}"
            // Input lebih dulu: modul tanpa masukan tak menyalurkan apa pun, jadi
            // itulah yang harus pertama terlihat saat kabinet baru dipasang.
            listOf(
                SplicePointView(
                    kind = ConnectionPointKind.SPLITTER_IN,
                    nodeId = module.id,
                    portNumber = null,
                    portSide = null,
                    label = "Input ${module.code}",
                    group = group,
                    connectionId = inputs[PointKey(module.id, null, null)],
                ),
            ) + (1..module.legCount).map { leg ->
                SplicePointView(
                    kind = ConnectionPointKind.SPLITTER_OUT,
                    nodeId = module.id,
                    portNumber = leg,
                    portSide = null,
                    label = "${module.code} kaki $leg",
                    group = group,
                    connectionId = legs[PointKey(module.id, leg, null)],
                )
            }
        }
    }

    /**
     * Kedua SISI tiap adapter dimunculkan terpisah, sebab keduanya memang dua
     * pekerjaan berbeda: belakang tempat pigtail dilas ke core kabel luar, depan
     * tempat patchcord dicolok ke OLT.
     */
    private fun odfPoints(closure: ClosureRef): List<SplicePointView> {
        val ports = closure.portCount ?: return emptyList()
        val used = occupancyOf(ConnectionPointKind.ODF_PORT, setOf(closure.id))
        val group = "Rak ${closure.code}"
        return (1..ports).flatMap { port ->
            OdfPortSide.entries.map { side ->
                SplicePointView(
                    kind = ConnectionPointKind.ODF_PORT,
                    nodeId = closure.id,
                    portNumber = port,
                    portSide = side,
                    label = "Port $port ${side.label.lowercase()}",
                    group = group,
                    connectionId = used[PointKey(closure.id, port, side)],
                )
            }
        }
    }

    /**
     * PON port OLT yang berdiri di POP yang sama dengan raknya. Dibatasi per POP
     * karena patchcord tak menyeberang gedung — menawarkan PON port kota sebelah
     * cuma memperbesar daftar dan peluang salah colok.
     */
    private fun ponPoints(closure: ClosureRef): List<SplicePointView> {
        val siteId = closure.siteId ?: return emptyList()
        val olts = oltRepository.findBySiteId(siteId)
        if (olts.isEmpty()) return emptyList()
        val ports = olts.associateWith { ponPortRepository.findByOltId(it.id) }
        val used = occupancyOf(
            ConnectionPointKind.PON_PORT,
            ports.values.flatMapTo(HashSet()) { list -> list.map { it.id } },
        )
        return ports.entries.flatMap { (olt, list) ->
            list.map { port ->
                SplicePointView(
                    kind = ConnectionPointKind.PON_PORT,
                    nodeId = port.id,
                    portNumber = null,
                    portSide = null,
                    label = "PON ${port.label}",
                    group = "${olt.code} · ${olt.name}",
                    connectionId = used[PointKey(port.id, null, null)],
                )
            }
        }
    }

    /** Identitas sebuah titik simpul: nomor & sisi ikut, sebab keduanya membedakan. */
    private data class PointKey(val nodeId: UUID, val portNumber: Int?, val portSide: OdfPortSide?)

    /** Siapa memakai titik yang mana — dicari global, seperti [assertFree] menilainya. */
    private fun occupancyOf(kind: ConnectionPointKind, nodeIds: Set<UUID>): Map<PointKey, UUID> =
        connections.findByNodeIds(kind, nodeIds)
            .flatMap { connection -> listOf(connection.a, connection.b).map { it to connection.id } }
            .filter { (point, _) -> point.kind == kind && point.nodeId in nodeIds }
            .associate { (point, id) -> PointKey(point.nodeId!!, point.portNumber, point.portSide) to id }

    /**
     * Kotak sambung punya batas fisik: tray-nya habis. Diperiksa saat menyambung,
     * bukan saat menggambar kabel, karena yang memakan tempat memang sambungannya
     * — core yang cuma lewat tak menghabiskan apa pun.
     */
    private fun assertRoomLeft(closure: ClosureRef) {
        val limit = closure.spliceCapacity ?: return
        val used = connections.countByClosureId(closure.id)
        if (used >= limit) {
            throw ConflictException(
                "${closure.code} sudah penuh: $used dari $limit sambungan terpakai",
            )
        }
    }

    private fun requireConnection(id: UUID): FiberConnection =
        connections.findById(id) ?: throw NotFoundException("Sambungan $id tidak ditemukan")

    private fun requireCore(id: UUID): CableCore =
        cableCoreRepository.findById(id) ?: throw NotFoundException("Core $id tidak ditemukan")

    // ------------------------------------------------------------------
    // Work order
    // ------------------------------------------------------------------

    /**
     * Menolak nomor WO yang tak ada SEBELUM seratnya tercatat tersambung.
     *
     * Di sini tegas, karena teknisi sendiri yang memilih tiketnya di layar:
     * salah ketik berarti pekerjaan hari itu menggantung di work order hantu dan
     * tak pernah ketemu lagi. Berbeda dengan penulisan linimasa yang menyusul —
     * lihat [noteToWorkOrder].
     */
    private fun requireWorkOrder(id: UUID) = workOrders.findWorkOrder(id)
        ?: throw NotFoundException("Work order $id tidak ditemukan")

    /**
     * Menempelkan sebaris jejak ke linimasa work order-nya, bila memang ada.
     *
     * Sengaja tak melempar apa pun: pekerjaan seratnya sudah tersimpan dan itulah
     * yang bernilai. Tiket yang lenyap di antara dua langkah tak boleh menggulung
     * balik sambungan yang secara fisik memang sudah dilas di dalam kotak.
     */
    private fun noteToWorkOrder(connection: FiberConnection, message: String) {
        val workOrderId = connection.workOrderId ?: return
        workOrders.noteSpliceActivity(workOrderId, message, currentUser.currentOrNull()?.userId)
    }

    /**
     * Memetakan sekaligus supaya asal-usul core (kabel, nomor, warna) diambil
     * dalam dua query, bukan dua query per baris sambungan.
     */
    private fun List<FiberConnection>.toViews(): List<FiberConnectionView> {
        if (isEmpty()) return emptyList()
        val cores = cableCoreRepository.findByIds(flatMap { it.coreIds }.distinct()).associateBy { it.id }
        val cables = cableRepository.findByIds(cores.values.map { it.cableId }.distinct()).associateBy { it.id }
        // Kode WO & nama teknisi diresolusi sekali untuk seluruh daftar. Satu kotak
        // 24 core berisi 24 baris; menanyakannya per baris berarti 48 pertanyaan
        // untuk satu layar yang isinya cuma dua nama.
        val tickets = mapNotNullTo(HashSet()) { it.workOrderId }.mapNotNull { workOrders.findWorkOrder(it) }.associateBy { it.id }
        val technicians = iam.usersByIds(mapNotNullTo(HashSet()) { it.splicedBy }).associateBy { it.id }
        // "PON port OLT" saja tak menolong siapa pun di depan rak — yang dicari
        // teknisi adalah labelnya (1/1/3). Diambil sekali untuk seluruh daftar.
        val ponPorts = flatMap { listOf(it.a, it.b) }
            .filter { it.kind == ConnectionPointKind.PON_PORT }
            .mapNotNullTo(HashSet()) { it.nodeId }
            .let { if (it.isEmpty()) emptyMap() else ponPortRepository.findAllByIds(it).associateBy { p -> p.id } }
        return map { connection ->
            FiberConnectionView(
                id = connection.id,
                closureKind = connection.closureKind,
                closureId = connection.closureId,
                a = connection.a.toView(cores, cables, ponPorts),
                b = connection.b.toView(cores, cables, ponPorts),
                method = connection.method,
                methodLabel = connection.method.label,
                lossDb = connection.lossDb,
                note = connection.note,
                workOrderId = connection.workOrderId,
                workOrderCode = connection.workOrderId?.let { tickets[it]?.code },
                splicedById = connection.splicedBy,
                splicedByName = connection.splicedBy?.let { technicians[it]?.name },
                splicedAt = connection.splicedAt,
            )
        }
    }

    private fun ConnectionPoint.toView(
        cores: Map<UUID, CableCore>,
        cables: Map<UUID, Cable>,
        ponPorts: Map<UUID, PonPort>,
    ): FiberConnectionPointView {
        val core = coreId?.let { cores[it] }
        val cable = core?.let { cables[it.cableId] }
        val ponPort = nodeId?.takeIf { kind == ConnectionPointKind.PON_PORT }?.let { ponPorts[it] }
        return FiberConnectionPointView(
            kind = kind,
            kindLabel = kind.label,
            label = core?.let { "Core ${it.coreNumber} · ${it.color.label}" + (cable?.let { c -> " · ${c.code}" } ?: "") }
                ?: ponPort?.let { "PON ${it.label}" }
                ?: description,
            coreId = coreId,
            cableId = core?.cableId,
            cableCode = cable?.code,
            coreNumber = core?.coreNumber,
            colorHex = core?.color?.hex,
            nodeId = nodeId,
            portNumber = portNumber,
            portSide = portSide,
        )
    }
}

private fun ConnectionPointCommand.toPoint() = ConnectionPoint(kind, coreId, nodeId, portNumber, portSide)

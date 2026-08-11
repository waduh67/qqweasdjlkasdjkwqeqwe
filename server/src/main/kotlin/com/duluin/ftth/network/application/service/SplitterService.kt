package com.duluin.ftth.network.application.service

import com.duluin.ftth.common.domain.error.ConflictException
import com.duluin.ftth.common.domain.error.NotFoundException
import com.duluin.ftth.common.domain.error.ValidationException
import com.duluin.ftth.common.infrastructure.audit.AuditRecorder
import com.duluin.ftth.common.security.CurrentUserProvider
import com.duluin.ftth.network.application.port.inbound.ClosureSplitterView
import com.duluin.ftth.network.application.port.inbound.ManageSplitterUseCase
import com.duluin.ftth.network.application.port.inbound.SaveSplitterCommand
import com.duluin.ftth.network.application.port.inbound.SplitterView
import com.duluin.ftth.network.application.port.inbound.UpdateSplitterCommand
import com.duluin.ftth.network.application.port.outbound.FiberConnectionRepository
import com.duluin.ftth.network.application.port.outbound.OdcRepository
import com.duluin.ftth.network.application.port.outbound.OdpRepository
import com.duluin.ftth.network.application.port.outbound.SplitterRepository
import com.duluin.ftth.network.domain.model.ClosureKind
import com.duluin.ftth.network.domain.model.ConnectionPointKind
import com.duluin.ftth.network.domain.model.Splitter
import com.duluin.ftth.network.domain.model.vo.SplitterRatio
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Kelola modul splitter di dalam ODC/ODP.
 *
 * Dua jalan masuk yang sengaja dibiarkan berdampingan:
 *
 *  - Layar splitter sendiri, untuk kabinet sungguhan yang berisi beberapa modul
 *    dengan rasio berbeda.
 *  - Jalan pintas satu isian "rasio splitter" di form ODC/ODP ([applyPrimaryRatio]),
 *    karena kotak ODP di tiang memang cuma berisi satu modul dan memaksa
 *    operator membuka layar kedua untuk itu adalah kerja tambahan tanpa guna.
 *
 * Aturan fisiknya sama lewat jalan mana pun — keduanya bermuara ke [create]/
 * [update]/[delete] yang satu ini.
 */
@Service
@Transactional
class SplitterService(
    private val splitters: SplitterRepository,
    private val odcRepository: OdcRepository,
    private val odpRepository: OdpRepository,
    private val connections: FiberConnectionRepository,
    private val currentUser: CurrentUserProvider,
    private val auditor: AuditRecorder,
) : ManageSplitterUseCase {

    @Transactional(readOnly = true)
    override fun list(ownerKind: ClosureKind, ownerId: UUID): ClosureSplitterView {
        val owner = requireOwner(ownerKind, ownerId)
        val contents = splitters.findByOwnerId(ownerId)
        return ClosureSplitterView(
            ownerKind = ownerKind,
            ownerId = ownerId,
            ownerCode = owner.code,
            ownerName = owner.name,
            splitters = contents.toViews(owner.code),
        )
    }

    override fun create(command: SaveSplitterCommand): SplitterView {
        val owner = requireOwner(command.ownerKind, command.ownerId)
        val code = command.code?.trim()?.takeIf { it.isNotEmpty() }?.uppercase()
            ?: nextCode(command.ownerId)
        if (splitters.existsByOwnerIdAndCode(command.ownerId, code)) {
            throw ConflictException("Kode splitter '$code' sudah dipakai di ${owner.code}")
        }
        val saved = splitters.save(
            Splitter.create(
                tenantId = currentUser.current().tenantId,
                ownerKind = command.ownerKind,
                ownerId = command.ownerId,
                code = code,
                ratio = SplitterRatio.of(command.ratio),
                note = command.note,
            ),
        )
        auditor.record(
            "splitter.created", command.ownerKind.name, command.ownerId, saved.tenantId,
            mapOf("owner" to owner.code, "code" to saved.code, "ratio" to saved.ratio.label),
        )
        return listOf(saved).toViews(owner.code).first()
    }

    override fun update(id: UUID, command: UpdateSplitterCommand): SplitterView {
        val splitter = requireSplitter(id)
        val owner = requireOwner(splitter.ownerKind, splitter.ownerId)
        splitter.update(SplitterRatio.of(command.ratio), command.note, usedLegsOf(id))
        val saved = splitters.save(splitter)
        auditor.record(
            "splitter.updated", splitter.ownerKind.name, splitter.ownerId, saved.tenantId,
            mapOf("owner" to owner.code, "code" to saved.code, "ratio" to saved.ratio.label),
        )
        return listOf(saved).toViews(owner.code).first()
    }

    override fun delete(id: UUID) {
        val splitter = requireSplitter(id)
        assertUnwired(splitter)
        splitters.deleteById(id)
        auditor.record(
            "splitter.deleted", splitter.ownerKind.name, splitter.ownerId, splitter.tenantId,
            mapOf("code" to splitter.code, "ratio" to splitter.ratio.label),
        )
    }

    // ------------------------------------------------------------------
    // Dipakai OdcService/OdpService
    // ------------------------------------------------------------------

    /** Isi beberapa kabinet sekaligus — satu query untuk satu halaman daftar ODC/ODP. */
    @Transactional(readOnly = true)
    fun contentsOf(ownerIds: Set<UUID>): Map<UUID, List<Splitter>> = splitters.findByOwnerIds(ownerIds)

    /**
     * Menerapkan isian "rasio splitter" dari form ODC/ODP.
     *
     * Kosong berarti kabinet tanpa splitter — sah, dan itu memang bentuk ODC
     * cross-connect. Kabinet yang sudah berisi LEBIH DARI SATU modul tak
     * disentuh: satu isian tak bisa mewakili tiga modul berbeda, dan menebak
     * mana yang dimaksud lebih berbahaya daripada diam. Kabinet seperti itu
     * dikelola lewat layar splitternya sendiri.
     */
    fun applyPrimaryRatio(ownerKind: ClosureKind, ownerId: UUID, ratio: String?) {
        val existing = splitters.findByOwnerId(ownerId)
        if (existing.size > 1) return
        val current = existing.firstOrNull()
        val wanted = ratio?.trim()?.takeIf { it.isNotEmpty() }
        when {
            wanted == null -> current?.let { delete(it.id) }
            current == null -> create(SaveSplitterCommand(ownerKind, ownerId, code = null, ratio = wanted, note = null))
            else -> update(current.id, UpdateSplitterCommand(ratio = wanted, note = current.note))
        }
    }

    /**
     * Mencabut seluruh modul sebuah kabinet yang akan dihapus. Modul yang masih
     * tersambung menahan penghapusan — kabinet lenyap sementara seratnya masih
     * terpasang di lapangan adalah kegagalan senyap yang baru ketahuan saat
     * menelusuri gangguan.
     */
    fun removeAllOf(ownerId: UUID) {
        val contents = splitters.findByOwnerId(ownerId)
        if (contents.isEmpty()) return
        contents.forEach { assertUnwired(it) }
        splitters.deleteAll(contents)
    }

    // ------------------------------------------------------------------
    // Aturan & pemuatan
    // ------------------------------------------------------------------

    private fun assertUnwired(splitter: Splitter) {
        val legs = usedLegsOf(splitter.id)
        if (legs.isNotEmpty()) {
            throw ConflictException(
                "Splitter ${splitter.code} masih menyambung di kaki ${legs.sorted().joinToString(", ")} — " +
                    "lepas dulu sambungannya",
            )
        }
        if (inputConnected(setOf(splitter.id)).contains(splitter.id)) {
            throw ConflictException("Input splitter ${splitter.code} masih tersambung — lepas dulu sambungannya")
        }
    }

    private fun usedLegsOf(splitterId: UUID): Set<Int> =
        connections.usedPortNumbersOfNodes(ConnectionPointKind.SPLITTER_OUT, setOf(splitterId))[splitterId]
            ?: emptySet()

    private fun inputConnected(splitterIds: Set<UUID>): Set<UUID> =
        connections.nodesWithPoint(ConnectionPointKind.SPLITTER_IN, splitterIds)

    private fun requireSplitter(id: UUID): Splitter =
        splitters.findById(id) ?: throw NotFoundException("Splitter $id tidak ditemukan")

    /** Kode berikutnya di kabinet: SPL-1, SPL-2, … melompati nomor yang sudah terpakai. */
    private fun nextCode(ownerId: UUID): String {
        val taken = splitters.findByOwnerId(ownerId).mapTo(HashSet()) { it.code }
        return generateSequence(1) { it + 1 }.map { "SPL-$it" }.first { it !in taken }
    }

    private fun requireOwner(ownerKind: ClosureKind, ownerId: UUID): Owner = when (ownerKind) {
        ClosureKind.ODC -> odcRepository.findById(ownerId)?.let { Owner(it.code, it.name) }
        ClosureKind.ODP -> odpRepository.findById(ownerId)?.let { Owner(it.code, it.name) }
        // Ditolak sebagai bentuk fisik, bukan sebagai id yang tak ketemu: joint
        // box & ODF memang tak pernah berisi splitter.
        ClosureKind.JOINT_BOX, ClosureKind.ODF -> throw ValidationException(
            "${ownerKind.label} tak berisi splitter — di dalamnya serat disambung langsung ke serat",
        )
    } ?: throw NotFoundException("${ownerKind.label} $ownerId tidak ditemukan")

    private data class Owner(val code: String, val name: String)

    /** Memetakan sekaligus supaya okupansi kaki diambil dua query, bukan dua per modul. */
    private fun List<Splitter>.toViews(ownerCode: String?): List<SplitterView> {
        if (isEmpty()) return emptyList()
        val ids = mapTo(HashSet()) { it.id }
        val legs = connections.usedPortNumbersOfNodes(ConnectionPointKind.SPLITTER_OUT, ids)
        val fed = inputConnected(ids)
        return map { splitter ->
            SplitterView(
                id = splitter.id,
                ownerKind = splitter.ownerKind,
                ownerId = splitter.ownerId,
                ownerCode = ownerCode,
                code = splitter.code,
                ratio = splitter.ratio.label,
                legCount = splitter.legCount,
                insertionLossDb = splitter.insertionLossDb,
                usedLegs = legs[splitter.id]?.sorted() ?: emptyList(),
                inputConnected = splitter.id in fed,
                note = splitter.note,
            )
        }
    }
}

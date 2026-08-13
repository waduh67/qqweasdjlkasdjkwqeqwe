package com.duluin.ftth.network.application.service

import com.duluin.ftth.network.CustomerEndpointProbe
import com.duluin.ftth.network.application.port.outbound.CableCoreRepository
import com.duluin.ftth.network.application.port.outbound.CableRepository
import com.duluin.ftth.network.application.port.outbound.FiberConnectionRepository
import com.duluin.ftth.network.domain.model.Cable
import com.duluin.ftth.network.domain.model.ConnectionPointKind
import com.duluin.ftth.network.domain.model.NetworkEndpoint
import com.duluin.ftth.network.domain.model.NetworkNodeKind
import com.duluin.ftth.network.domain.model.Splitter
import org.springframework.stereotype.Component
import java.util.UUID

/** Sebuah kaki: modul mana, nomor berapa. */
data class LegKey(val splitterId: UUID, val legNumber: Int)

/**
 * Ke mana ujung sebuah kaki splitter sesungguhnya bermuara.
 *
 * Dibaca dari SERAT — sambungan → core → kabel → ujung kabel — bukan dari catatan
 * pemasangan ONU. Justru bedanya yang berguna: catatan bisa mendahului pekerjaan
 * (ONU didaftarkan di port 1, kakinya belum dilas) dan bisa ketinggalan (drop
 * dipindah ke kaki lain, catatannya tetap), dan satu-satunya cara mengetahuinya
 * adalah menelusuri keduanya lalu menyandingkannya.
 */
data class LegLoad(
    val splitterId: UUID,
    val legNumber: Int,
    val connectionId: UUID,
    /** Kabel yang dilas ke kaki ini; null bila kaki menyuapi titik non-core. */
    val cableId: UUID?,
    val cableCode: String?,
    /**
     * Nomor port faceplate yang TERCATAT di ujung kabel drop — "drop ini dicolok
     * di lubang berapa" menurut data kabelnya. Null bila memang tak dicatat.
     */
    val cablePortNumber: Int?,
    /** Rumah pelanggan di seberang kabel; null bila kabelnya belum sampai rumah. */
    val customerId: UUID?,
    /** Nama penghuni rumah itu; null bila pelanggannya sudah terhapus. */
    val customerName: String?,
    /** Kotak di seberang, bila kaki ini menyuapi kotak lanjutan (kaskade ODP). */
    val downstreamCode: String?,
    /** Modul yang INPUT-nya disuapi kaki ini — splitter bertingkat sekabinet. */
    val cascadeToCode: String?,
    /**
     * Kaki justru diarahkan balik ke kabel yang menyuapi input modulnya sendiri.
     *
     * Sambungan begini tak bisa lagi dibuat (ditolak saat menyambung), tapi yang
     * terlanjur ada di data lama tetap harus kelihatan — di lapangan artinya
     * cahaya yang sudah dibagi pulang ke ODC dan tak seorang pun terlayani.
     */
    val backward: Boolean,
) {
    /** Kalimat pendek untuk label kaki di meja sambung dan papan port. */
    fun describe(): String = when {
        backward -> "${cableCode ?: "kabel"} · BALIK ke penyuap input"
        customerId != null -> listOfNotNull(cableCode, customerName ?: "pelanggan sudah dihapus")
            .joinToString(" · ")
        cascadeToCode != null -> "menyuapi $cascadeToCode"
        downstreamCode != null -> listOfNotNull(cableCode, downstreamCode).joinToString(" → ")
        cableCode != null -> "$cableCode · belum sampai pelanggan"
        else -> "tersambung"
    }
}

/**
 * Menelusuri isi tiap kaki splitter sebuah kotak dalam sekali jalan.
 *
 * Ada sebagai komponen tersendiri karena dua pemakainya menanya hal yang sama
 * dengan tujuan berbeda: meja sambung memberinya label ("kaki 3 · DROP-… ·
 * Budi") supaya teknisi tak perlu menghafal, dan papan port ODP memakainya
 * sebagai pembanding catatan pemasangan. Menyalin penelusurannya ke dua tempat
 * berarti suatu hari keduanya menjawab berbeda untuk kaki yang sama — dan yang
 * berdiri di depan kotak tak tahu mana yang benar.
 *
 * Semuanya dibatch: satu query sambungan, satu query core, satu query kabel,
 * berapa pun banyak modul di dalam kotaknya.
 */
@Component
class SplitterLegTracer(
    private val connections: FiberConnectionRepository,
    private val cableCoreRepository: CableCoreRepository,
    private val cableRepository: CableRepository,
    private val closures: ClosureLookup,
    /** Kosong bila module yang memiliki data pelanggan tak ikut dijalankan. */
    private val customerNames: List<CustomerEndpointProbe>,
) {
    /**
     * Isi tiap kaki [modules] yang terpasang di kotak [closureId].
     *
     * Kaki yang belum dilas tak muncul di hasil — "tak ada kuncinya" berarti
     * kaki itu masih bebas, dan itu keadaan yang sah, bukan kekurangan data.
     */
    fun trace(closureId: UUID, modules: List<Splitter>): Map<LegKey, LegLoad> {
        val ids = modules.mapTo(HashSet()) { it.id }
        if (ids.isEmpty()) return emptyMap()

        val legs = connections.findByNodeIds(ConnectionPointKind.SPLITTER_OUT, ids).mapNotNull { row ->
            val here = listOf(row.a, row.b).firstOrNull {
                it.kind == ConnectionPointKind.SPLITTER_OUT && it.nodeId in ids && it.portNumber != null
            } ?: return@mapNotNull null
            val far = row.opposite(here) ?: return@mapNotNull null
            Triple(row.id, here, far)
        }
        if (legs.isEmpty()) return emptyMap()

        val cores = cableCoreRepository.findByIds(legs.mapNotNull { (_, _, far) -> far.coreId })
            .associateBy { it.id }
        val cables = cableRepository.findByIds(cores.values.mapTo(HashSet()) { it.cableId })
            .associateBy { it.id }
        // Kotak di seberang tiap kabel, sekali cari untuk semuanya. Rumah
        // pelanggan bukan kotak, jadi memang tak muncul di sini — dan tak perlu.
        val farBoxes = closures.findAll(cables.values.map { it.awayFrom(closureId).ref })
        val feedingInputs = cablesWiredTo(ConnectionPointKind.SPLITTER_IN, ids)
        val moduleCodes = modules.associate { it.id to it.code }
        val names = namesOf(
            cables.values.mapNotNullTo(HashSet()) { cable ->
                cable.awayFrom(closureId).takeIf { it.kind == NetworkNodeKind.CUSTOMER }?.id
            },
        )

        return legs.associate { (connectionId, leg, far) ->
            val splitterId = requireNotNull(leg.nodeId)
            val legNumber = requireNotNull(leg.portNumber)
            val cable = far.coreId?.let { cores[it] }?.let { cables[it.cableId] }
            val away = cable?.awayFrom(closureId)
            val customerId = away?.takeIf { it.kind == NetworkNodeKind.CUSTOMER }?.id
            LegKey(splitterId, legNumber) to LegLoad(
                splitterId = splitterId,
                legNumber = legNumber,
                connectionId = connectionId,
                cableId = cable?.id,
                cableCode = cable?.code,
                cablePortNumber = cable?.attachmentAt(closureId)?.node?.portNumber,
                customerId = customerId,
                customerName = customerId?.let { names[it] },
                downstreamCode = away?.let { farBoxes[it.id] }?.code,
                cascadeToCode = far.nodeId
                    ?.takeIf { far.kind == ConnectionPointKind.SPLITTER_IN }
                    ?.let { moduleCodes[it] ?: "splitter lain" },
                backward = cable != null && cable.id in feedingInputs[splitterId].orEmpty(),
            )
        }
    }

    /**
     * Kabel yang seratnya sudah dilas ke titik [kind] milik tiap splitter.
     *
     * Inilah bahan satu-satunya untuk menilai "kaki ini berbalik ke penyuapnya
     * sendiri", dan karena penilaian itu dipakai dua kali — menolak sambungan
     * baru saat menyambung, lalu menandai data lama di papan port — jawabannya
     * harus datang dari satu tempat. Kalau tidak, aturan dan tandanya bisa
     * berselisih, dan yang di lapangan melihat kaki bertanda merah yang katanya
     * boleh disambung.
     */
    fun cablesWiredTo(kind: ConnectionPointKind, splitterIds: Set<UUID>): Map<UUID, Set<UUID>> {
        if (splitterIds.isEmpty()) return emptyMap()
        val far = connections.findByNodeIds(kind, splitterIds).mapNotNull { row ->
            val here = listOf(row.a, row.b).firstOrNull { it.kind == kind && it.nodeId in splitterIds }
                ?: return@mapNotNull null
            row.opposite(here)?.coreId?.let { here.nodeId!! to it }
        }
        if (far.isEmpty()) return emptyMap()
        val cableOf = cableCoreRepository.findByIds(far.map { it.second })
            .associate { it.id to it.cableId }
        return far.groupBy({ it.first }, { cableOf[it.second] })
            .mapValues { (_, list) -> list.filterNotNull().toSet() }
    }

    private fun namesOf(customerIds: Set<UUID>): Map<UUID, String> =
        if (customerIds.isEmpty()) {
            emptyMap()
        } else {
            customerNames.fold(emptyMap()) { acc, probe -> acc + probe.namesOf(customerIds) }
        }

    /**
     * Ujung kabel di seberang kotak ini — sebagaimana rutenya digambar. Kabel yang
     * cuma dikupas di tengah tak punya "seberang" yang tegas, jadi yang diambil
     * ujung akhirnya: ke arah situlah seratnya melanjut.
     */
    private fun Cable.awayFrom(nodeId: UUID): NetworkEndpoint = if (to.id == nodeId) from else to
}

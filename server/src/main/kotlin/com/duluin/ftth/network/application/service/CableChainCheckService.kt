package com.duluin.ftth.network.application.service

import com.duluin.ftth.common.domain.error.NotFoundException
import com.duluin.ftth.network.application.port.inbound.CableChainView
import com.duluin.ftth.network.application.port.inbound.ChainVerdict
import com.duluin.ftth.network.application.port.inbound.CheckCableChainUseCase
import com.duluin.ftth.network.application.port.outbound.CableRepository
import com.duluin.ftth.network.application.port.outbound.FiberConnectionRepository
import com.duluin.ftth.network.application.port.outbound.OdpRepository
import com.duluin.ftth.network.application.port.outbound.SplitterRepository
import com.duluin.ftth.network.domain.model.Cable
import com.duluin.ftth.network.domain.model.ConnectionPointKind
import com.duluin.ftth.network.domain.model.NetworkNodeKind
import com.duluin.ftth.network.domain.model.NetworkNodeRef
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Membaca baris sambungan untuk memisahkan rantai ODP → ODP yang sungguhan dari
 * yang cuma penyamar satu selubung menerus — lihat [CheckCableChainUseCase]
 * untuk duduk perkaranya.
 *
 * Urutan pemeriksaannya sengaja dari bukti yang paling kuat ke yang paling
 * lemah, dan berhenti begitu ada yang cukup:
 *
 * 1. Ada kaki splitter kotak hulu yang menyuapi core kabel ini → splitter
 *    bertingkat. Selesai, tak ada yang salah.
 * 2. Ada core kabel ini yang disambung LURUS ke core kabel lain di kotak itu →
 *    seratnya menerus melewati kotak; ruasnya dipecah, entah karena penyamaran
 *    lama atau karena memang dua haspel yang bertemu.
 * 3. Tak ada catatan apa pun, tapi ada kabel masuk dengan jumlah core yang sama
 *    → dugaan dari BENTUK data, dan disebut apa adanya sebagai dugaan.
 * 4. Selain itu: belum bisa dipastikan. Diam lebih baik daripada menuduh.
 */
@Service
@Transactional(readOnly = true)
class CableChainCheckService(
    private val cableRepository: CableRepository,
    private val connections: FiberConnectionRepository,
    private val splitters: SplitterRepository,
    private val odpRepository: OdpRepository,
) : CheckCableChainUseCase {

    override fun check(cableId: UUID): CableChainView {
        val cable = cableRepository.findById(cableId)
            ?: throw NotFoundException("Kabel $cableId tidak ditemukan")
        if (cable.from.kind != NetworkNodeKind.ODP || cable.to.kind != NetworkNodeKind.ODP) {
            return CableChainView(
                cableId = cable.id,
                verdict = ChainVerdict.NOT_CHAINED,
                headline = "Kabel ini bukan ruas antar-kotak, jadi tak ada rantai yang perlu diperiksa.",
                evidence = emptyList(),
            )
        }

        val hulu = cable.from.id
        val kode = odpRepository.findById(hulu)?.code ?: "kotak hulu"
        val diKotak = connections.findByCableId(cable.id).filter { it.closureId == hulu }

        val modules = splitters.findByOwnerId(hulu).associateBy { it.id }
        val legs = diKotak.mapNotNull { conn ->
            listOf(conn.a, conn.b).firstOrNull { it.kind == ConnectionPointKind.SPLITTER_OUT && it.nodeId in modules }
        }.mapNotNull { it.portNumber }.distinct().sorted()
        if (legs.isNotEmpty()) return cascade(cable, kode, legs)

        val lurus = diKotak.count { conn -> conn.a.kind == ConnectionPointKind.CORE && conn.b.kind == ConnectionPointKind.CORE }
        if (lurus > 0) return spliceThrough(cable, kode, lurus)

        val sepadan = incomingCables(hulu, cable.id).firstOrNull { it.coreCount == cable.coreCount }
        return if (sepadan != null) suspectByShape(cable, kode, sepadan, modules.isEmpty()) else unknown(cable, kode)
    }

    /** Kabel yang BERAKHIR di kotak hulu — calon "selubung sesungguhnya". */
    private fun incomingCables(hulu: UUID, exclude: UUID): List<Cable> {
        val ref = NetworkNodeRef(NetworkNodeKind.ODP, hulu)
        return cableRepository.findByEndpoint(ref).filter { it.id != exclude && it.to.ref == ref }
    }

    private fun cascade(cable: Cable, kode: String, legs: List<Int>) = CableChainView(
        cableId = cable.id,
        verdict = ChainVerdict.CASCADE,
        headline = "Rantai yang sah: kaki splitter $kode menyuapi kotak berikutnya.",
        evidence = listOf(
            "Kaki ${legs.joinToString(", ")} di $kode tersambung ke core kabel ini — " +
                "artinya cahaya memang dipecah dulu di sini, baru diteruskan.",
        ),
        upstreamClosureCode = kode,
        cascadeLegs = legs,
    )

    private fun spliceThrough(cable: Cable, kode: String, lurus: Int) = CableChainView(
        cableId = cable.id,
        verdict = ChainVerdict.SUSPECT,
        headline = "Seratnya menerus melewati $kode — kotak ini cuma tempat sambungannya.",
        evidence = listOf(
            "$lurus core kabel ini disambung LURUS ke core kabel lain di $kode, " +
                "tanpa lewat kaki splitter satu pun.",
            "Simulasi putus & telusur jalur sudah membaca sambungan itu, jadi angkanya tidak keliru. " +
                "Yang masih meleset cuma panjang material: tiap ruas menghitung slack-nya sendiri.",
        ),
        suggestion = "Kalau di lapangan ini SATU haspel yang dikupas di $kode, gambar ulang jadi satu " +
            "kabel menerus lalu hapus ruas perantaranya. Kalau memang dua haspel yang bertemu di situ, " +
            "biarkan apa adanya — catatan sambungannya sudah benar.",
        upstreamClosureCode = kode,
    )

    private fun suspectByShape(cable: Cable, kode: String, sepadan: Cable, tanpaModul: Boolean) = CableChainView(
        cableId = cable.id,
        verdict = ChainVerdict.SUSPECT,
        headline = "Berpola selubung yang dipecah, bukan splitter bertingkat.",
        evidence = buildList {
            add(
                "Tak satu pun kaki splitter di $kode menyuapi core kabel ini" +
                    if (tanpaModul) " — kotak itu bahkan belum berisi modul splitter." else ".",
            )
            add(
                "Kabel ${sepadan.code} masuk ke $kode dengan jumlah core yang sama " +
                    "(${cable.coreCount}) — pola khas satu selubung yang diteruskan.",
            )
            add(
                "Dugaan ini dibaca dari bentuk data, bukan dari isi kotaknya. " +
                    "Catat sambungan di $kode dan pemeriksaan ini akan menjawab dengan pasti.",
            )
        },
        suggestion = "Kalau ${sepadan.code} dan kabel ini sebenarnya satu selubung: gambar satu kabel " +
            "sampai kotak terakhir, kupas di tiap kotak lewat meja sambung, lalu hapus ruas perantaranya. " +
            "Panjang material berhenti dihitung dobel dan simulasi putus jadi jujur.",
        upstreamClosureCode = kode,
        upstreamCableId = sepadan.id,
        upstreamCableCode = sepadan.code,
    )

    private fun unknown(cable: Cable, kode: String) = CableChainView(
        cableId = cable.id,
        verdict = ChainVerdict.UNKNOWN,
        headline = "Belum bisa dipastikan rantai ini sah atau penyamar.",
        evidence = listOf(
            "Belum ada sambungan tercatat di $kode yang menyentuh core kabel ini, dan tak ada kabel " +
                "masuk dengan jumlah core yang sama.",
            "Catat isi $kode di meja sambung — satu baris kaki splitter ↔ core sudah cukup untuk " +
                "menjawabnya.",
        ),
        upstreamClosureCode = kode,
    )
}

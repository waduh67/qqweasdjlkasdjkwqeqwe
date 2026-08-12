package com.duluin.ftth.network.application.service

import com.duluin.ftth.common.domain.error.NotFoundException
import com.duluin.ftth.common.domain.geo.Coordinate
import com.duluin.ftth.network.application.port.outbound.JointBoxRepository
import com.duluin.ftth.network.application.port.outbound.OdcRepository
import com.duluin.ftth.network.application.port.outbound.OdfRepository
import com.duluin.ftth.network.application.port.outbound.OdpRepository
import com.duluin.ftth.network.domain.model.ClosureKind
import com.duluin.ftth.network.domain.model.JointBox
import com.duluin.ftth.network.domain.model.NetworkNodeRef
import com.duluin.ftth.network.domain.model.Odc
import com.duluin.ftth.network.domain.model.Odf
import com.duluin.ftth.network.domain.model.Odp
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Sebuah kotak sambung, apa pun jenisnya, dalam satu bentuk.
 *
 * ODC, ODP, joint box, dan ODF adalah empat agregat berbeda dengan bidang yang
 * berbeda pula, tapi bagi urusan serat keempatnya cuma "kotak yang dibuka di
 * suatu titik": ia punya kode, letak, dan batas isi. Bentuk seragam ini yang
 * membuat satu meja kerja splicing dan satu penelusur jalur bisa melayani
 * keempatnya tanpa cabang `when` di mana-mana.
 */
data class ClosureRef(
    val kind: ClosureKind,
    val id: UUID,
    val code: String,
    val name: String,
    val location: Coordinate,
    /**
     * Batas jumlah sambungan yang muat di dalam kotaknya; null = tak dibatasi.
     * Joint box dibatasi jumlah tray, ODF jumlah sisi adapter. ODC/ODP tak
     * dibatasi di sini — yang membatasinya adalah kaki modul splitternya
     * masing-masing, dan itu diperiksa saat kaki ditunjuk.
     */
    val spliceCapacity: Int? = null,
    /** Jumlah adapter rak; null untuk kotak selain ODF. */
    val portCount: Int? = null,
    /** POP tempat rak berdiri — sumber daftar PON port; null untuk kotak lain. */
    val siteId: UUID? = null,
)

/**
 * Mencari kotak sambung lintas jenis.
 *
 * Dipisahkan jadi komponen sendiri karena dipakai lebih dari satu layanan (meja
 * kerja splicing dan penelusur jalur), dan menyalin `when (kind)` empat cabang
 * ke tiap pemakai berarti suatu hari salah satu salinan ketinggalan saat jenis
 * kotak kelima muncul.
 */
@Component
class ClosureLookup(
    private val odcRepository: OdcRepository,
    private val odpRepository: OdpRepository,
    private val jointBoxRepository: JointBoxRepository,
    private val odfRepository: OdfRepository,
) {
    fun find(kind: ClosureKind, id: UUID): ClosureRef? = when (kind) {
        ClosureKind.ODC -> odcRepository.findById(id)?.toRef()
        ClosureKind.ODP -> odpRepository.findById(id)?.toRef()
        ClosureKind.JOINT_BOX -> jointBoxRepository.findById(id)?.toRef()
        ClosureKind.ODF -> odfRepository.findById(id)?.toRef()
    }

    fun require(kind: ClosureKind, id: UUID): ClosureRef =
        find(kind, id) ?: throw NotFoundException("${kind.label} $id tidak ditemukan")

    /**
     * Kotak untuk sekumpulan rujukan simpul sekaligus, terkunci per id.
     *
     * Ada supaya layar yang menyebut BANYAK kotak — daftar kabel beserta barisan
     * singgahan masing-masing, misalnya — tidak menembakkan satu query per kotak.
     * Simpul yang memang bukan kotak (POP, OLT, rumah pelanggan) tak muncul di
     * hasil, begitu pula kotak yang sudah terhapus; pemanggil memperlakukan
     * keduanya sama: tak ada label untuk ditampilkan.
     */
    fun findAll(refs: Collection<NetworkNodeRef>): Map<UUID, ClosureRef> = refs
        .mapNotNull { ref -> ClosureKind.of(ref.kind)?.let { it to ref.id } }
        .groupBy({ it.first }, { it.second })
        .flatMap { (kind, ids) -> findAll(kind, ids.toSet()) }
        .associateBy { it.id }

    private fun findAll(kind: ClosureKind, ids: Set<UUID>): List<ClosureRef> = when (kind) {
        ClosureKind.ODC -> odcRepository.findAllByIds(ids).map { it.toRef() }
        ClosureKind.ODP -> odpRepository.findAllByIds(ids).map { it.toRef() }
        ClosureKind.JOINT_BOX -> jointBoxRepository.findAllByIds(ids).map { it.toRef() }
        ClosureKind.ODF -> odfRepository.findAllByIds(ids).map { it.toRef() }
    }

    private fun Odc.toRef() = ClosureRef(ClosureKind.ODC, id, code, name, location)

    private fun Odp.toRef() = ClosureRef(ClosureKind.ODP, id, code, name, location)

    private fun JointBox.toRef() =
        ClosureRef(ClosureKind.JOINT_BOX, id, code, name, location, spliceCapacity = capacity)

    /**
     * Batas rak dihitung dari SISI, bukan port: tiap adapter memang menampung dua
     * sambungan, belakang dan depan.
     */
    private fun Odf.toRef() = ClosureRef(
        ClosureKind.ODF, id, code, name, location,
        spliceCapacity = portCount * 2,
        portCount = portCount,
        siteId = siteId,
    )
}

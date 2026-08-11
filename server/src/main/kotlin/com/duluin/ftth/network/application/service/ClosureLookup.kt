package com.duluin.ftth.network.application.service

import com.duluin.ftth.common.domain.error.NotFoundException
import com.duluin.ftth.common.domain.geo.Coordinate
import com.duluin.ftth.network.application.port.outbound.JointBoxRepository
import com.duluin.ftth.network.application.port.outbound.OdcRepository
import com.duluin.ftth.network.application.port.outbound.OdfRepository
import com.duluin.ftth.network.application.port.outbound.OdpRepository
import com.duluin.ftth.network.domain.model.ClosureKind
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
        ClosureKind.ODC -> odcRepository.findById(id)?.let {
            ClosureRef(kind, it.id, it.code, it.name, it.location)
        }
        ClosureKind.ODP -> odpRepository.findById(id)?.let {
            ClosureRef(kind, it.id, it.code, it.name, it.location)
        }
        ClosureKind.JOINT_BOX -> jointBoxRepository.findById(id)?.let {
            ClosureRef(kind, it.id, it.code, it.name, it.location, spliceCapacity = it.capacity)
        }
        // Batas rak dihitung dari SISI, bukan port: tiap adapter memang menampung
        // dua sambungan, belakang dan depan.
        ClosureKind.ODF -> odfRepository.findById(id)?.let {
            ClosureRef(
                kind, it.id, it.code, it.name, it.location,
                spliceCapacity = it.portCount * 2,
                portCount = it.portCount,
                siteId = it.siteId,
            )
        }
    }

    fun require(kind: ClosureKind, id: UUID): ClosureRef =
        find(kind, id) ?: throw NotFoundException("${kind.label} $id tidak ditemukan")
}

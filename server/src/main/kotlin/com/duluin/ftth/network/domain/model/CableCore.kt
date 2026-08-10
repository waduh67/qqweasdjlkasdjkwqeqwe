package com.duluin.ftth.network.domain.model

import com.duluin.ftth.common.domain.UuidV7
import com.duluin.ftth.common.domain.error.ValidationException
import java.util.UUID

/**
 * Keadaan sehelai core. Dipakai operator untuk memutuskan serat mana yang boleh
 * dipakai pemasangan berikutnya.
 */
enum class CoreStatus(val label: String) {
    /** Belum dipakai — kandidat pemasangan berikutnya. */
    FREE("Bebas"),

    /** Sudah menyalurkan layanan. */
    USED("Terpakai"),

    /** Dibooking untuk rencana yang sudah pasti (survey/WO terjadwal). */
    RESERVED("Dicadangkan"),

    /** Putus atau redamannya di luar batas — jangan dipakai. */
    DAMAGED("Rusak"),
}

/**
 * Sehelai serat di dalam sebuah kabel.
 *
 * Inilah unit yang sesungguhnya mengalirkan layanan: satu ODP makan satu core,
 * jadi kabel 8 core melayani 8 ODP dalam SATU selubung. Kabel cuma pembungkus
 * dan rute; sambungan (fase berikutnya) menempel ke core, bukan ke kabel.
 *
 * Warna tidak disimpan — diturunkan dari nomor lewat urutan baku [FiberColor].
 * Menyimpannya cuma menciptakan kemungkinan data yang bertengkar dengan
 * standar yang sudah pasti.
 */
class CableCore private constructor(
    val id: UUID,
    val tenantId: UUID,
    val cableId: UUID,
    val tubeNumber: Int,
    val coreNumber: Int,
    status: CoreStatus,
    note: String?,
) {
    var status: CoreStatus = status
        private set

    var note: String? = note
        private set

    /** Posisi core di dalam tube-nya (1..[CORES_PER_TUBE]) — penentu warnanya. */
    val positionInTube: Int get() = ((coreNumber - 1) % CORES_PER_TUBE) + 1

    /** Warna selubung core, seperti yang dilihat teknisi saat tube dibuka. */
    val color: FiberColor get() = FiberColor.ofPosition(positionInTube)

    /** Warna tube tempat core ini berada — pembeda "core biru" di kabel besar. */
    val tubeColor: FiberColor get() = FiberColor.ofPosition(tubeNumber)

    /** Core yang tak boleh dijadikan tujuan pemasangan baru. */
    val available: Boolean get() = status == CoreStatus.FREE

    fun update(status: CoreStatus, note: String?) {
        this.status = status
        this.note = sanitizeNote(note)
    }

    companion object {
        /**
         * Isi satu tube menurut praktik kabel loose-tube yang lazim. Kabel di bawah
         * dua belas core berarti satu tube yang tak penuh — itu normal.
         */
        const val CORES_PER_TUBE = 12

        const val MAX_NOTE_LENGTH = 200

        /**
         * Membuat barisan core untuk rentang nomor [from]..[to] (1-based, inklusif).
         * Dipakai saat kabel dibuat (1..coreCount) maupun saat jumlah corenya
         * dinaikkan (lama+1..baru) — core lama beserta status & catatannya tak
         * pernah disentuh, jadi menaikkan kapasitas tidak menghapus riwayat.
         */
        fun generate(tenantId: UUID, cableId: UUID, from: Int, to: Int): List<CableCore> =
            (from..to).map { number ->
                CableCore(
                    id = UuidV7.generate(),
                    tenantId = tenantId,
                    cableId = cableId,
                    tubeNumber = ((number - 1) / CORES_PER_TUBE) + 1,
                    coreNumber = number,
                    status = CoreStatus.FREE,
                    note = null,
                )
            }

        fun rehydrate(
            id: UUID,
            tenantId: UUID,
            cableId: UUID,
            tubeNumber: Int,
            coreNumber: Int,
            status: CoreStatus,
            note: String?,
        ): CableCore = CableCore(id, tenantId, cableId, tubeNumber, coreNumber, status, note)

        private fun sanitizeNote(note: String?): String? {
            val trimmed = note?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            if (trimmed.length > MAX_NOTE_LENGTH) {
                throw ValidationException("Catatan core maksimal $MAX_NOTE_LENGTH karakter")
            }
            return trimmed
        }
    }
}

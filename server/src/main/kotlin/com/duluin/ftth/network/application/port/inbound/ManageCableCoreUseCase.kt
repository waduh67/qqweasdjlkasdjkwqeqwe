package com.duluin.ftth.network.application.port.inbound

import com.duluin.ftth.network.domain.model.CoreStatus
import java.util.UUID

/**
 * Kelola core sebuah kabel: lihat barisan seratnya, lalu setel status &
 * catatan — satu core atau sekaligus banyak.
 *
 * Sengaja hanya "setel", tanpa tambah/hapus core: jumlah core adalah sifat
 * FISIK kabel, jadi barisannya lahir & menyusut mengikuti `coreCount` kabel
 * (lihat CableService). Layar core tak boleh bisa mengarang serat yang tak ada
 * di selubungnya.
 */
interface ManageCableCoreUseCase {

    fun list(cableId: UUID): CableCoreListView

    fun update(cableId: UUID, command: UpdateCableCoresCommand): CableCoreListView
}

/**
 * Perubahan atas sekumpulan core sekaligus. Bidang yang null berarti TIDAK
 * DIUBAH — supaya "tandai 6 core ini terpakai" tak diam-diam menghapus catatan
 * lapangan yang berbeda-beda di tiap core.
 */
data class UpdateCableCoresCommand(
    /** Nomor core yang disasar; minimal satu. */
    val coreNumbers: List<Int>,
    val status: CoreStatus? = null,
    val note: String? = null,
    /** Kosongkan catatan. Dipisah dari [note] karena null = "jangan diubah". */
    val clearNote: Boolean = false,
)

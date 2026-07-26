package com.duluin.ftth.bng.application.port.inbound

import com.duluin.ftth.bng.domain.model.NasVendor
import java.util.UUID

/** Kelola registri BRAS/NAS milik tenant. */
interface ManageNasUseCase {

    fun list(): List<NasView>

    fun get(id: UUID): NasView

    fun create(command: SaveNasCommand): NasView

    fun update(id: UUID, command: SaveNasCommand): NasView

    /** Menolak menghapus BRAS yang masih menaungi akun PPPoE mana pun. */
    fun delete(id: UUID)
}

/**
 * [coaSecret] null/kosong saat update berarti "biarkan apa adanya" — rahasia tak
 * terhapus tanpa sengaja saat operator menyunting field lain. [enabled] hanya
 * berpengaruh saat update; NAS baru selalu aktif.
 */
data class SaveNasCommand(
    val name: String,
    val vendor: NasVendor,
    val address: String?,
    val nasIdentifier: String?,
    val coaSecret: String?,
    val collectorId: UUID?,
    val enabled: Boolean = true,
)

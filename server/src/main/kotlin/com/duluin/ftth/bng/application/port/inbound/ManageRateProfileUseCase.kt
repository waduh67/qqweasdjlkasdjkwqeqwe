package com.duluin.ftth.bng.application.port.inbound

import java.util.UUID

/** Kelola katalog paket (rate profile) milik tenant. */
interface ManageRateProfileUseCase {

    fun list(): List<RateProfileView>

    fun get(id: UUID): RateProfileView

    fun create(command: SaveRateProfileCommand): RateProfileView

    fun update(id: UUID, command: SaveRateProfileCommand): RateProfileView

    /** Menolak menghapus paket yang masih dipakai akun PPPoE mana pun. */
    fun delete(id: UUID)
}

data class SaveRateProfileCommand(
    val name: String,
    val description: String?,
    val downMbps: Int,
    val upMbps: Int,
    val radiusProfileName: String?,
)

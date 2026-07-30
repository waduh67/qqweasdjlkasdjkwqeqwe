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

    /**
     * Koordinat FreeRADIUS pusat (host+port) yang tenant arahkan router-nya. Info platform
     * global — dari [com.duluin.ftth.bng.config.RadiusProperties], bukan tabel `nas`.
     */
    fun radiusEndpoint(): RadiusEndpointView
}

/**
 * [coaSecret]/[apiSecret] null/kosong saat update berarti "biarkan apa adanya" — rahasia
 * tak terhapus tanpa sengaja saat operator menyunting field lain. [enabled] hanya
 * berpengaruh saat update; NAS baru selalu aktif.
 *
 * [apiUsername]/[apiSecret]/[apiPort]/[apiUseTls] adalah kredensial kontrol REST RouterOS
 * (vendor MIKROTIK). Berbeda dengan secret, field non-rahasia selalu ditimpa nilai baru
 * saat update (cermin [enabled]).
 */
@Suppress("LongParameterList")
data class SaveNasCommand(
    val name: String,
    val vendor: NasVendor,
    val address: String?,
    val nasIdentifier: String?,
    val coaSecret: String?,
    val collectorId: UUID?,
    val enabled: Boolean = true,
    val apiUsername: String? = null,
    val apiSecret: String? = null,
    val apiPort: Int? = null,
    val apiUseTls: Boolean = true,
)

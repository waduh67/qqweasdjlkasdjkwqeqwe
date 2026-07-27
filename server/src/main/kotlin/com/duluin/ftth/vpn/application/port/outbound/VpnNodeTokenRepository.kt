package com.duluin.ftth.vpn.application.port.outbound

import com.duluin.ftth.vpn.domain.model.VpnNodeToken
import java.util.UUID

/** Rujukan ringkas hasil resolusi token node: cukup untuk memasang tenant lalu memuat hub. */
data class VpnNodeRef(val serverId: UUID, val tenantId: UUID)

/**
 * Port persistence token node. Tabelnya SENGAJA tanpa RLS/@TenantId (cermin collector):
 * [findRefByTokenHash] dipanggil sebelum tenant diketahui, jadi tak boleh tersaring tenant.
 */
interface VpnNodeTokenRepository {

    fun save(token: VpnNodeToken): VpnNodeToken

    /** Cari rujukan hub dari hash token — lintas-tenant, dipakai saat autentikasi node. */
    fun findRefByTokenHash(tokenHash: String): VpnNodeRef?

    /** Satu token aktif per hub; hapus yang lama sebelum menerbitkan pengganti. */
    fun deleteByServerId(serverId: UUID)
}

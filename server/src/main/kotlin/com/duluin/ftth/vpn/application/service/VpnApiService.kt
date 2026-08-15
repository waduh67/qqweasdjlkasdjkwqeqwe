package com.duluin.ftth.vpn.application.service

import com.duluin.ftth.vpn.VpnApi
import com.duluin.ftth.vpn.VpnTunnelRef
import com.duluin.ftth.vpn.application.port.outbound.VpnServerRepository
import com.duluin.ftth.vpn.domain.model.TunnelSubnet
import com.duluin.ftth.vpn.domain.model.VpnServerStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Implementasi kontrak lintas-modul vpn. Hanya hub berstatus ACTIVE yang disebut —
 * hub yang dimatikan tak sedang mengangkut siapa pun, jadi menyarankan alamatnya
 * kepada operator justru menyesatkan.
 *
 * Alamat hub diturunkan dari [TunnelSubnet] (network+1), bukan disimpan sendiri,
 * supaya tak pernah beda dari yang benar-benar dipasang di config OpenVPN.
 */
@Service
@Transactional(readOnly = true)
class VpnApiService(
    private val serverRepository: VpnServerRepository,
) : VpnApi {

    override fun overlayTunnels(): List<VpnTunnelRef> =
        serverRepository.findAll()
            .filter { it.status == VpnServerStatus.ACTIVE }
            .mapNotNull { server ->
                runCatching { TunnelSubnet.parse(server.tunnelCidr) }.getOrNull()?.let {
                    VpnTunnelRef(tunnelCidr = server.tunnelCidr, serverAddress = it.serverAddress())
                }
            }
            .distinctBy { it.tunnelCidr }

    /**
     * Alamat yang tak berbentuk IPv4 (nama host, isian setengah jadi) bukan kesalahan yang
     * perlu diteriakkan — ia cuma bukan penghuni tunnel. `contains` melempar untuk masukan
     * begitu, jadi lemparannya diserap di sini dan dijawab "bukan".
     */
    override fun tunnelContaining(address: String): VpnTunnelRef? {
        val candidate = address.trim().ifBlank { return null }
        return serverRepository.findAll()
            .filter { it.status == VpnServerStatus.ACTIVE }
            .firstNotNullOfOrNull { server ->
                val subnet = runCatching { TunnelSubnet.parse(server.tunnelCidr) }.getOrNull()
                    ?: return@firstNotNullOfOrNull null
                val inside = runCatching { subnet.contains(candidate) }.getOrDefault(false)
                if (inside) {
                    VpnTunnelRef(tunnelCidr = server.tunnelCidr, serverAddress = subnet.serverAddress())
                } else {
                    null
                }
            }
    }
}

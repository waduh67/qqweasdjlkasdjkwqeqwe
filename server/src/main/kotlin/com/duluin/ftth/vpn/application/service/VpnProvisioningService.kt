package com.duluin.ftth.vpn.application.service

import com.duluin.ftth.common.domain.error.NotFoundException
import com.duluin.ftth.vpn.application.port.inbound.ProvisionVpnNodeUseCase
import org.springframework.stereotype.Service

/**
 * Orkestrator provisioning node. Token menaut ke HUB saja (infrastruktur platform); hub &
 * akun kini tabel tanpa RLS, jadi peer di-resolve lintas-tenant lewat (serverId, username)
 * tanpa perlu memasang tenant context. [VpnNodeAuthenticator] tetap REQUIRES_NEW agar sesi
 * pencarian token menutup rapi sebelum pembacaan berikutnya.
 */
@Service
class VpnProvisioningService(
    private val authenticator: VpnNodeAuthenticator,
    private val reader: VpnProvisioningReader,
) : ProvisionVpnNodeUseCase {

    override fun renderInstaller(rawToken: String, appBaseUrl: String): String {
        val ref = authenticator.resolve(rawToken)
            ?: throw NotFoundException("Token node VPN tidak dikenal")
        return reader.renderInstaller(ref.serverId, rawToken, appBaseUrl)
    }

    override fun authenticate(rawToken: String, username: String, password: String): Boolean {
        val ref = authenticator.resolve(rawToken) ?: return false
        return reader.verifyCredentials(ref.serverId, username, password)
    }

    override fun clientConnectLine(rawToken: String, username: String): String? {
        val ref = authenticator.resolve(rawToken) ?: return null
        return reader.clientConnectLine(ref.serverId, username)
    }

    override fun reportConnected(rawToken: String, username: String): Boolean {
        val ref = authenticator.resolve(rawToken) ?: return false
        return reader.recordConnect(ref.serverId, username)
    }

    override fun reportDisconnected(rawToken: String, username: String): Boolean {
        val ref = authenticator.resolve(rawToken) ?: return false
        return reader.recordDisconnect(ref.serverId, username)
    }
}

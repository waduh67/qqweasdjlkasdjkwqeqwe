package com.duluin.ftth.vpn.application.service

import com.duluin.ftth.common.domain.error.NotFoundException
import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.vpn.application.port.inbound.ProvisionVpnNodeUseCase
import org.springframework.stereotype.Service

/**
 * Orkestrator provisioning node. SENGAJA tanpa `@Transactional` di level kelas: ia harus
 * (1) me-resolve token lewat [VpnNodeAuthenticator] (REQUIRES_NEW, tabel tanpa RLS) dengan
 * sesi yang menutup dulu, LALU (2) memasang tenant via [TenantContext.runAs] dan membaca hub
 * ber-RLS lewat [VpnProvisioningReader]. Jika kelas ini transaksional, sesi ROOT-tenant akan
 * telanjur terbuka sebelum tenant di-set — kegagalan senyap seperti jebakan open-in-view.
 */
@Service
class VpnProvisioningService(
    private val authenticator: VpnNodeAuthenticator,
    private val reader: VpnProvisioningReader,
) : ProvisionVpnNodeUseCase {

    override fun renderInstaller(rawToken: String, appBaseUrl: String): String {
        val ref = authenticator.resolve(rawToken)
            ?: throw NotFoundException("Token node VPN tidak dikenal")
        return TenantContext.runAs(ref.tenantId) {
            reader.renderInstaller(ref.serverId, rawToken, appBaseUrl)
        }
    }

    override fun authenticate(rawToken: String, username: String, password: String): Boolean {
        val ref = authenticator.resolve(rawToken) ?: return false
        return TenantContext.runAs(ref.tenantId) {
            reader.verifyCredentials(ref.serverId, username, password)
        }
    }

    override fun clientConnectLine(rawToken: String, username: String): String? {
        val ref = authenticator.resolve(rawToken) ?: return null
        return TenantContext.runAs(ref.tenantId) {
            reader.clientConnectLine(ref.serverId, username)
        }
    }
}

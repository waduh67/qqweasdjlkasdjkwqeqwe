package com.duluin.ftth.monitoring.adapter.inbound.security

import org.springframework.security.authentication.AbstractAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import java.util.UUID

/**
 * Identitas collector yang sudah terautentikasi.
 *
 * Bukan `AuthenticatedUser`: collector adalah mesin, bukan orang. Ia tidak punya
 * izin RBAC, tidak punya batasan area, dan tidak boleh menyentuh API pengguna —
 * memisahkan tipenya membuat percampuran itu mustahil terjadi tanpa sengaja.
 */
data class CollectorPrincipal(
    val collectorId: UUID,
    val tenantId: UUID,
    val name: String,
)

class CollectorAuthenticationToken(
    val principalDetails: CollectorPrincipal,
) : AbstractAuthenticationToken(listOf(SimpleGrantedAuthority(ROLE))) {

    init {
        isAuthenticated = true
    }

    override fun getPrincipal(): Any = principalDetails

    /** Kredensialnya sudah diverifikasi dan sengaja tidak disimpan. */
    override fun getCredentials(): Any? = null

    override fun getName(): String = principalDetails.name

    companion object {
        const val ROLE = "ROLE_COLLECTOR"
    }
}

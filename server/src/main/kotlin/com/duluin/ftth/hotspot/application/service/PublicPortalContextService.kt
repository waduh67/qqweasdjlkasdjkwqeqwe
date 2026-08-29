package com.duluin.ftth.hotspot.application.service

import com.duluin.ftth.common.infrastructure.audit.AuditRecorder
import com.duluin.ftth.common.infrastructure.config.SecurityProperties
import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.hotspot.PortalContextProperties
import com.duluin.ftth.hotspot.application.port.inbound.InvalidPortalContextException
import com.duluin.ftth.hotspot.application.port.inbound.IssuePortalContextCommand
import com.duluin.ftth.hotspot.application.port.inbound.IssuedPortalContext
import com.duluin.ftth.hotspot.application.port.inbound.ResolvePublicPortalContextUseCase
import com.duluin.ftth.hotspot.application.port.inbound.ResolvedPortalContext
import com.duluin.ftth.hotspot.application.port.outbound.HotspotSiteRepository
import com.duluin.ftth.hotspot.domain.model.HotspotSite
import com.duluin.ftth.hotspot.domain.model.PortalMode
import com.nimbusds.jose.jwk.source.ImmutableSecret
import com.nimbusds.jose.proc.SecurityContext
import org.springframework.security.oauth2.jose.jws.MacAlgorithm
import org.springframework.security.oauth2.jwt.JwtClaimsSet
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.JwtEncoderParameters
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.net.URI
import java.time.Clock
import java.time.Instant
import java.util.UUID
import javax.crypto.spec.SecretKeySpec

@Service
class PublicPortalContextService(
    private val sites: HotspotSiteRepository,
    private val properties: PortalContextProperties,
    securityProperties: SecurityProperties,
    private val audit: AuditRecorder,
    private val clock: Clock,
) : ResolvePublicPortalContextUseCase {
    private val key = SecretKeySpec(securityProperties.effectivePortalJwtSecret.toByteArray(), "HmacSHA256")
    private val encoder = NimbusJwtEncoder(ImmutableSecret<SecurityContext>(key))
    private val decoder: JwtDecoder = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build()

    @Transactional(readOnly = true)
    override fun issue(command: IssuePortalContextCommand): IssuedPortalContext {
        val site = sites.findPublicByPortalId(command.portalId) ?: throw invalid()
        validateHosted(site)
        val issuedAt = Instant.now(clock)
        val expiresAt = issuedAt.plus(properties.ttl)
        val redirectUrl = safeRedirect(command.originalUrl)
        val claims = JwtClaimsSet.builder()
            .issuedAt(issuedAt)
            .expiresAt(expiresAt)
            .claim(CLAIM_USE, TOKEN_USE)
            .claim(CLAIM_TENANT_ID, site.tenantId.toString())
            .claim(CLAIM_SITE_ID, site.id.toString())
            .claim(CLAIM_NAS_ID, site.nasId.toString())
            .apply {
                redirectUrl?.let { claim(CLAIM_REDIRECT_URL, it) }
                command.clientMac?.trim()?.takeIf { it.isNotEmpty() }?.let { claim(CLAIM_CLIENT_MAC, it) }
                command.clientIp?.trim()?.takeIf { it.isNotEmpty() }?.let { claim(CLAIM_CLIENT_IP, it) }
            }
            .build()
        return IssuedPortalContext(
            state = encoder.encode(JwtEncoderParameters.from(org.springframework.security.oauth2.jwt.JwsHeader.with(MacAlgorithm.HS256).build(), claims)).tokenValue,
            expiresAt = expiresAt,
        )
    }

    @Transactional(readOnly = true)
    override fun resolve(state: String): ResolvedPortalContext {
        val claims = runCatching { decoder.decode(state.trim()) }.getOrElse { throw invalid() }
        if (claims.getClaimAsString(CLAIM_USE) != TOKEN_USE) throw invalid()
        val tenantId = claims.uuid(CLAIM_TENANT_ID) ?: throw invalid()
        val siteId = claims.uuid(CLAIM_SITE_ID) ?: throw invalid()
        val nasId = claims.uuid(CLAIM_NAS_ID) ?: throw invalid()
        val site = TenantContext.runAs(tenantId) { sites.findById(siteId) } ?: throw invalid()
        if (site.tenantId != tenantId || site.nasId != nasId) throw invalid()
        validateHosted(site)
        val redirectUrl = safeRedirect(claims.getClaimAsString(CLAIM_REDIRECT_URL))
        return ResolvedPortalContext(
            displayName = site.branding.displayName ?: site.name,
            logoUrl = site.branding.logoUrl,
            redirectUrl = redirectUrl,
            clientMac = claims.getClaimAsString(CLAIM_CLIENT_MAC),
            clientIp = claims.getClaimAsString(CLAIM_CLIENT_IP),
        )
    }

    fun auditFailure(portalId: String?) {
        val site = portalId?.let { sites.findPublicByPortalId(it) } ?: return
        TenantContext.runAs(site.tenantId) {
            audit.record(
                action = "HOTSPOT_PORTAL_CONTEXT_REJECTED",
                entityType = "HotspotSite",
                entityId = site.id,
                tenantId = site.tenantId,
            )
        }
    }

    private fun validateHosted(site: HotspotSite) {
        if (site.portalMode != PortalMode.NETOPS_HOSTED) throw invalid()
    }

    private fun safeRedirect(value: String?): String? {
        val raw = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val uri = runCatching { URI(raw) }.getOrNull() ?: throw invalid()
        if (uri.userInfo != null || uri.fragment != null || uri.scheme !in setOf("http", "https")) throw invalid()
        val host = uri.host?.lowercase() ?: throw invalid()
        if (host !in properties.allowedRedirectHosts.map(String::lowercase).toSet()) throw invalid()
        return uri.toASCIIString()
    }

    private fun org.springframework.security.oauth2.jwt.Jwt.uuid(name: String): UUID? =
        getClaimAsString(name)?.let { runCatching { UUID.fromString(it) }.getOrNull() }

    private fun invalid(): InvalidPortalContextException = InvalidPortalContextException()

    private companion object {
        const val TOKEN_USE = "hotspot-portal-context"
        const val CLAIM_USE = "use"
        const val CLAIM_TENANT_ID = "tenant_id"
        const val CLAIM_SITE_ID = "site_id"
        const val CLAIM_NAS_ID = "nas_id"
        const val CLAIM_REDIRECT_URL = "redirect_url"
        const val CLAIM_CLIENT_MAC = "client_mac"
        const val CLAIM_CLIENT_IP = "client_ip"
    }
}

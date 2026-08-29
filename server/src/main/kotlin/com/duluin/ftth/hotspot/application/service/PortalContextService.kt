package com.duluin.ftth.hotspot.application.service

import com.duluin.ftth.common.infrastructure.config.SecurityProperties
import com.duluin.ftth.hotspot.application.port.outbound.HotspotSiteRepository
import com.duluin.ftth.hotspot.domain.model.PortalMode
import org.springframework.stereotype.Service
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

@Service
class PortalContextService(
    private val sites: HotspotSiteRepository,
    securityProperties: SecurityProperties,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val signingKey = SecretKeySpec(
        securityProperties.effectivePortalJwtSecret.toByteArray(StandardCharsets.UTF_8),
        "HmacSHA256",
    )

    fun issue(portalId: String, returnPath: String?): IssuedPortalContext {
        val site = findHostedSite(portalId) ?: throw InvalidPortalContextException()
        val safeReturnPath = returnPath?.takeIf(::isSafeReturnPath)
            ?: if (returnPath == null) null else throw InvalidPortalContextException()
        val expiresAt = clock.instant().plus(STATE_TTL)
        val payload = listOf(site.tenantId, site.id, site.nasId, expiresAt.epochSecond, safeReturnPath.orEmpty()).joinToString("|")
        return IssuedPortalContext(sign(payload), expiresAt)
    }

    fun resolve(portalId: String, state: String): PortalContext {
        val site = findHostedSite(portalId) ?: throw InvalidPortalContextException()
        val payload = verify(state) ?: throw InvalidPortalContextException()
        val fields = payload.split('|', limit = 5)
        if (fields.size != 5) throw InvalidPortalContextException()
        val tenantId = fields[0].toUuidOrNull()
        val siteId = fields[1].toUuidOrNull()
        val nasId = fields[2].toUuidOrNull()
        val expiresAt = fields[3].toLongOrNull()
        val returnPath = fields[4].ifBlank { null }
        if (
            tenantId != site.tenantId || siteId != site.id || nasId != site.nasId ||
            expiresAt == null || expiresAt <= clock.instant().epochSecond ||
            (returnPath != null && !isSafeReturnPath(returnPath))
        ) throw InvalidPortalContextException()
        return PortalContext(site.name, site.branding.displayName, site.branding.logoUrl, returnPath)
    }

    private fun findHostedSite(portalId: String) = sites.findByPortalId(portalId)
        ?.takeIf { it.portalMode == PortalMode.NETOPS_HOSTED }

    private fun sign(payload: String): String {
        val encodedPayload = Base64.getUrlEncoder().withoutPadding().encodeToString(payload.toByteArray(StandardCharsets.UTF_8))
        return "$encodedPayload.${signature(encodedPayload)}"
    }

    private fun verify(state: String): String? {
        val parts = state.split('.', limit = 2)
        if (parts.size != 2 || !constantTimeEquals(parts[1], signature(parts[0]))) return null
        return runCatching { String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8) }.getOrNull()
    }

    private fun signature(value: String): String = Base64.getUrlEncoder().withoutPadding().encodeToString(
        Mac.getInstance("HmacSHA256").apply { init(signingKey) }.doFinal(value.toByteArray(StandardCharsets.UTF_8)),
    )

    private fun constantTimeEquals(left: String, right: String): Boolean = MessageDigest.isEqual(
        left.toByteArray(StandardCharsets.UTF_8),
        right.toByteArray(StandardCharsets.UTF_8),
    )

    private fun String.toUuidOrNull(): UUID? = runCatching(UUID::fromString).getOrNull()

    private fun isSafeReturnPath(value: String): Boolean =
        value.startsWith('/') && !value.startsWith("//") && !value.contains('\\') && !value.contains('\r') && !value.contains('\n')

    private companion object {
        val STATE_TTL: Duration = Duration.ofMinutes(5)
    }
}

data class IssuedPortalContext(val state: String, val expiresAt: Instant)

data class PortalContext(
    val siteName: String,
    val displayName: String?,
    val logoUrl: String?,
    val returnPath: String?,
)

class InvalidPortalContextException : RuntimeException()

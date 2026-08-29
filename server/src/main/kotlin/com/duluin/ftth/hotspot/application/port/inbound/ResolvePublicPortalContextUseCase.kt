package com.duluin.ftth.hotspot.application.port.inbound

import java.time.Instant

/**
 * Batas publik captive portal. Semua pengenal tenant/site/NAS diperoleh dari
 * [portalId] yang acak di server; browser tidak pernah mengirim pengenal itu.
 */
interface ResolvePublicPortalContextUseCase {
    fun issue(command: IssuePortalContextCommand): IssuedPortalContext
    fun resolve(state: String): ResolvedPortalContext
}

data class IssuePortalContextCommand(
    val portalId: String,
    val clientMac: String? = null,
    val clientIp: String? = null,
    val originalUrl: String? = null,
)

data class IssuedPortalContext(
    val state: String,
    val expiresAt: Instant,
)

data class ResolvedPortalContext(
    val displayName: String,
    val logoUrl: String?,
    val redirectUrl: String?,
    val clientMac: String?,
    val clientIp: String?,
)

/** Intentionally generic: public callers must not learn which check failed. */
class InvalidPortalContextException : RuntimeException("Konteks portal tidak valid")

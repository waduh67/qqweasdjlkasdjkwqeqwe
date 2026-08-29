package com.duluin.ftth.hotspot.domain.model

import com.duluin.ftth.common.domain.UuidV7
import com.duluin.ftth.common.domain.error.ValidationException
import java.security.SecureRandom
import java.util.UUID

enum class PortalMode { OFF, NAS_OWNED, NETOPS_HOSTED }

data class HotspotSiteBranding(
    val displayName: String?,
    val logoUrl: String?,
) {
    init {
        requireOptional(displayName, "Nama tampilan portal", 100)
        requireOptional(logoUrl, "URL logo portal", 500)
    }

    private fun requireOptional(value: String?, field: String, maximumLength: Int) {
        require(value == null || (value.isNotBlank() && value.length <= maximumLength)) {
            "$field tidak valid"
        }
    }
}

class HotspotSite private constructor(
    val id: UUID,
    val tenantId: UUID,
    val nasId: UUID,
    val portalId: String,
    name: String,
    location: String?,
    portalMode: PortalMode,
    branding: HotspotSiteBranding,
    defaultPlanId: UUID?,
) {
    var name: String = validatedName(name)
        private set
    var location: String? = validatedLocation(location)
        private set
    var portalMode: PortalMode = portalMode
        private set
    var branding: HotspotSiteBranding = branding
        private set
    var defaultPlanId: UUID? = defaultPlanId
        private set

    fun update(
        name: String,
        location: String?,
        portalMode: PortalMode,
        branding: HotspotSiteBranding,
        defaultPlanId: UUID?,
    ) {
        this.name = validatedName(name)
        this.location = validatedLocation(location)
        this.portalMode = portalMode
        this.branding = branding
        this.defaultPlanId = defaultPlanId
    }

    companion object {
        private const val PORTAL_ID_LENGTH = 22
        private val portalIdRandom = SecureRandom()
        private val portalIdPattern = Regex("^[A-Za-z0-9_-]{$PORTAL_ID_LENGTH}$")

        fun create(
            tenantId: UUID,
            nasId: UUID,
            name: String,
            location: String?,
            portalMode: PortalMode,
            branding: HotspotSiteBranding = HotspotSiteBranding(null, null),
            defaultPlanId: UUID? = null,
        ): HotspotSite = HotspotSite(
            id = UuidV7.generate(),
            tenantId = tenantId,
            nasId = nasId,
            portalId = generatePortalId(),
            name = name,
            location = location,
            portalMode = portalMode,
            branding = branding,
            defaultPlanId = defaultPlanId,
        )

        fun rehydrate(
            id: UUID,
            tenantId: UUID,
            nasId: UUID,
            portalId: String,
            name: String,
            location: String?,
            portalMode: PortalMode,
            branding: HotspotSiteBranding,
            defaultPlanId: UUID?,
        ): HotspotSite {
            require(portalIdPattern.matches(portalId)) { "Portal ID tidak aman" }
            return HotspotSite(id, tenantId, nasId, portalId, name, location, portalMode, branding, defaultPlanId)
        }

        private fun generatePortalId(): String {
            val bytes = ByteArray(16).also(portalIdRandom::nextBytes)
            return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        }

        private fun validatedName(value: String): String = value.trim().also {
            if (it.isEmpty() || it.length > 120) throw ValidationException("Nama hotspot site wajib diisi dan maksimal 120 karakter")
        }

        private fun validatedLocation(value: String?): String? = value?.trim()?.takeIf { it.isNotEmpty() }?.also {
            if (it.length > 300) throw ValidationException("Lokasi hotspot site maksimal 300 karakter")
        }
    }
}

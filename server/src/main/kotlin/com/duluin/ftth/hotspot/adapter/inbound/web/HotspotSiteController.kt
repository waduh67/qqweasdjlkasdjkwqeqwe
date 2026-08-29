package com.duluin.ftth.hotspot.adapter.inbound.web

import com.duluin.ftth.hotspot.application.port.inbound.CreateHotspotSiteCommand
import com.duluin.ftth.hotspot.application.port.inbound.ManageHotspotSiteUseCase
import com.duluin.ftth.hotspot.application.port.inbound.UpdateHotspotSiteCommand
import com.duluin.ftth.hotspot.domain.model.HotspotSite
import com.duluin.ftth.hotspot.domain.model.HotspotSiteBranding
import com.duluin.ftth.hotspot.domain.model.PortalMode
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import org.springframework.http.HttpStatus
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/hotspot/sites")
@Tag(name = "Hotspot & Voucher")
@SecurityRequirement(name = "bearer-jwt")
class HotspotSiteController(private val sites: ManageHotspotSiteUseCase) {
    @GetMapping
    @PreAuthorize("@authz.can('hotspot.site.view')")
    fun list(): List<HotspotSiteResponse> = sites.list().map(HotspotSiteResponse::from)

    @GetMapping("/{siteId}")
    @PreAuthorize("@authz.can('hotspot.site.view')")
    fun get(@PathVariable siteId: UUID): HotspotSiteResponse = HotspotSiteResponse.from(sites.get(siteId))

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@authz.can('hotspot.site.manage')")
    fun create(@Valid @RequestBody request: SaveHotspotSiteRequest): HotspotSiteResponse =
        HotspotSiteResponse.from(sites.create(request.toCreateCommand()))

    @PutMapping("/{siteId}")
    @PreAuthorize("@authz.can('hotspot.site.manage')")
    fun update(@PathVariable siteId: UUID, @Valid @RequestBody request: UpdateHotspotSiteRequest): HotspotSiteResponse =
        HotspotSiteResponse.from(sites.update(siteId, request.toCommand()))
}

data class SaveHotspotSiteRequest(
    @field:NotNull val nasId: UUID?,
    @field:NotBlank val name: String,
    val location: String? = null,
    @field:NotNull val portalMode: PortalMode?,
    @field:Valid val branding: HotspotSiteBrandingRequest? = null,
    val defaultPlanId: UUID? = null,
) {
    fun toCreateCommand() = CreateHotspotSiteCommand(
        nasId = requireNotNull(nasId),
        name = name,
        location = location,
        portalMode = requireNotNull(portalMode),
        branding = branding?.toDomain() ?: HotspotSiteBranding(null, null),
        defaultPlanId = defaultPlanId,
    )
}

data class UpdateHotspotSiteRequest(
    @field:NotBlank val name: String,
    val location: String? = null,
    @field:NotNull val portalMode: PortalMode?,
    @field:Valid val branding: HotspotSiteBrandingRequest? = null,
    val defaultPlanId: UUID? = null,
) {
    fun toCommand() = UpdateHotspotSiteCommand(
        name = name,
        location = location,
        portalMode = requireNotNull(portalMode),
        branding = branding?.toDomain() ?: HotspotSiteBranding(null, null),
        defaultPlanId = defaultPlanId,
    )
}

data class HotspotSiteBrandingRequest(val displayName: String? = null, val logoUrl: String? = null) {
    fun toDomain() = HotspotSiteBranding(displayName, logoUrl)
}

data class HotspotSiteResponse(
    val id: UUID,
    val nasId: UUID,
    val portalId: String,
    val name: String,
    val location: String?,
    val portalMode: PortalMode,
    val branding: HotspotSiteBrandingRequest,
    val defaultPlanId: UUID?,
) {
    companion object {
        fun from(site: HotspotSite) = HotspotSiteResponse(
            id = site.id,
            nasId = site.nasId,
            portalId = site.portalId,
            name = site.name,
            location = site.location,
            portalMode = site.portalMode,
            branding = HotspotSiteBrandingRequest(site.branding.displayName, site.branding.logoUrl),
            defaultPlanId = site.defaultPlanId,
        )
    }
}

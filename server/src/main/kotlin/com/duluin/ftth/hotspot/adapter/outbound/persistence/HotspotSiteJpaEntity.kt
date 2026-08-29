package com.duluin.ftth.hotspot.adapter.outbound.persistence

import com.duluin.ftth.common.infrastructure.persistence.TenantAwareJpaEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.util.UUID

@Entity
@Table(name = "hotspot_site")
class HotspotSiteJpaEntity(
    id: UUID,
    @Column(name = "nas_id", nullable = false)
    var nasId: UUID,
    @Column(name = "portal_id", nullable = false, unique = true, length = 22)
    var portalId: String,
    @Column(nullable = false, length = 120)
    var name: String,
    @Column(length = 300)
    var location: String?,
    @Column(name = "portal_mode", nullable = false, length = 20)
    var portalMode: String,
    @Column(name = "branding_display_name", length = 100)
    var brandingDisplayName: String?,
    @Column(name = "branding_logo_url", length = 500)
    var brandingLogoUrl: String?,
    @Column(name = "default_plan_id")
    var defaultPlanId: UUID?,
) : TenantAwareJpaEntity(id)

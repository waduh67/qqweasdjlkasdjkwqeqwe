package com.duluin.ftth.hotspot.application.service

import com.duluin.ftth.bng.BngApi
import com.duluin.ftth.catalog.CatalogApi
import com.duluin.ftth.common.domain.error.ConflictException
import com.duluin.ftth.common.domain.error.NotFoundException
import com.duluin.ftth.common.infrastructure.audit.AuditRecorder
import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.hotspot.HotspotApi
import com.duluin.ftth.hotspot.application.port.inbound.CreateHotspotSiteCommand
import com.duluin.ftth.hotspot.application.port.inbound.ManageHotspotSiteUseCase
import com.duluin.ftth.hotspot.application.port.inbound.UpdateHotspotSiteCommand
import com.duluin.ftth.hotspot.application.port.outbound.HotspotSiteRepository
import com.duluin.ftth.hotspot.domain.model.HotspotSite
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional
class HotspotSiteService(
    private val hotspotSiteRepository: HotspotSiteRepository,
    private val bngApi: BngApi,
    private val catalogApi: CatalogApi,
    private val auditor: AuditRecorder? = null,
) : ManageHotspotSiteUseCase, HotspotApi {
    @Transactional(readOnly = true)
    override fun findSite(siteId: java.util.UUID): HotspotSite? = hotspotSiteRepository.findById(siteId)

    @Transactional(readOnly = true)
    override fun list(): List<HotspotSite> = hotspotSiteRepository.findAll()

    @Transactional(readOnly = true)
    override fun get(siteId: java.util.UUID): HotspotSite =
        hotspotSiteRepository.findById(siteId) ?: throw NotFoundException("Hotspot site tidak ditemukan")

    override fun create(command: CreateHotspotSiteCommand): HotspotSite {
        if (!bngApi.hasNas(command.nasId)) throw NotFoundException("NAS tidak ditemukan")
        if (hotspotSiteRepository.findByNasId(command.nasId) != null) {
            throw ConflictException("NAS sudah terikat ke hotspot site")
        }
        validateDefaultPlan(command.defaultPlanId)
        val site = hotspotSiteRepository.save(
            HotspotSite.create(
                tenantId = TenantContext.tenantId(),
                nasId = command.nasId,
                name = command.name,
                location = command.location,
                portalMode = command.portalMode,
                branding = command.branding,
                defaultPlanId = command.defaultPlanId,
            ),
        )
        auditSiteConfiguration("HOTSPOT_SITE_CREATED", site)
        return site
    }

    override fun update(siteId: java.util.UUID, command: UpdateHotspotSiteCommand): HotspotSite {
        val site = get(siteId)
        validateDefaultPlan(command.defaultPlanId)
        site.update(command.name, command.location, command.portalMode, command.branding, command.defaultPlanId)
        return hotspotSiteRepository.save(site).also { auditSiteConfiguration("HOTSPOT_SITE_CONFIGURED", it) }
    }

    private fun auditSiteConfiguration(action: String, site: HotspotSite) {
        auditor?.record(
            action = action,
            entityType = "HotspotSite",
            entityId = site.id,
            tenantId = site.tenantId,
            detail = mapOf("nasId" to site.nasId, "portalMode" to site.portalMode.name, "defaultPlanId" to site.defaultPlanId),
        )
    }

    private fun validateDefaultPlan(planId: java.util.UUID?) {
        if (planId != null && catalogApi.findActiveHotspotPlan(planId) == null) {
            throw NotFoundException("Paket HOTSPOT aktif tidak ditemukan")
        }
    }
}

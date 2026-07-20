package com.duluin.ftth.network.application.service

import com.duluin.ftth.common.domain.Page
import com.duluin.ftth.common.domain.PageRequest
import com.duluin.ftth.common.domain.error.ConflictException
import com.duluin.ftth.common.domain.error.NotFoundException
import com.duluin.ftth.common.infrastructure.audit.AuditRecorder
import com.duluin.ftth.common.security.CurrentUserProvider
import com.duluin.ftth.network.application.port.inbound.ManageSiteUseCase
import com.duluin.ftth.network.application.port.inbound.SaveSiteCommand
import com.duluin.ftth.network.application.port.inbound.SiteView
import com.duluin.ftth.network.application.port.outbound.OltRepository
import com.duluin.ftth.network.application.port.outbound.SiteRepository
import com.duluin.ftth.network.domain.model.Site
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
@Transactional
class SiteService(
    private val siteRepository: SiteRepository,
    private val oltRepository: OltRepository,
    private val currentUser: CurrentUserProvider,
    private val auditor: AuditRecorder,
) : ManageSiteUseCase {

    @Transactional(readOnly = true)
    override fun search(query: String, pageRequest: PageRequest): Page<SiteView> {
        val page = siteRepository.search(query, pageRequest)
        val oltCounts = oltRepository.countBySiteIds(page.content.mapTo(HashSet()) { it.id })
        return page.map { it.toView(oltCounts[it.id] ?: 0) }
    }

    @Transactional(readOnly = true)
    override fun get(id: UUID): SiteView = requireSite(id).toView(oltRepository.countBySiteId(id))

    override fun create(command: SaveSiteCommand): SiteView {
        val code = command.code.trim().uppercase()
        if (siteRepository.existsByCode(code)) throw ConflictException("Kode site '$code' sudah dipakai")
        val site = siteRepository.save(
            Site.create(
                tenantId = currentUser.current().tenantId,
                code = command.code,
                name = command.name,
                address = command.address,
                location = command.location,
                areaId = command.areaId,
            ),
        )
        auditor.record("site.created", "Site", site.id, site.tenantId, mapOf("code" to site.code))
        return site.toView(0)
    }

    override fun update(id: UUID, command: SaveSiteCommand): SiteView {
        val site = requireSite(id)
        site.update(command.name, command.address, command.location, command.areaId)
        val saved = siteRepository.save(site)
        auditor.record("site.updated", "Site", saved.id, saved.tenantId, mapOf("code" to saved.code))
        return saved.toView(oltRepository.countBySiteId(id))
    }

    override fun delete(id: UUID) {
        val site = requireSite(id)
        val oltCount = oltRepository.countBySiteId(id)
        if (oltCount > 0) {
            throw ConflictException("Site ${site.code} masih menaungi $oltCount OLT, pindahkan atau hapus dulu")
        }
        siteRepository.deleteById(id)
        auditor.record("site.deleted", "Site", id, site.tenantId, mapOf("code" to site.code))
    }

    private fun requireSite(id: UUID): Site =
        siteRepository.findById(id) ?: throw NotFoundException("Site $id tidak ditemukan")
}

private fun Site.toView(oltCount: Long) = SiteView(
    id = id,
    code = code,
    name = name,
    address = address,
    location = location,
    areaId = areaId,
    oltCount = oltCount,
)

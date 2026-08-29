package com.duluin.ftth.hotspot

import com.duluin.ftth.bng.*
import com.duluin.ftth.catalog.CatalogApi
import com.duluin.ftth.catalog.PlanCommercialRef
import com.duluin.ftth.catalog.PlanNetworkRef
import com.duluin.ftth.common.domain.error.ConflictException
import com.duluin.ftth.common.domain.error.NotFoundException
import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.hotspot.application.port.inbound.CreateHotspotSiteCommand
import com.duluin.ftth.hotspot.application.port.inbound.UpdateHotspotSiteCommand
import com.duluin.ftth.hotspot.application.port.outbound.HotspotSiteRepository
import com.duluin.ftth.hotspot.application.service.HotspotSiteService
import com.duluin.ftth.hotspot.domain.model.HotspotSite
import com.duluin.ftth.hotspot.domain.model.PortalMode
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.UUID

class HotspotSiteServiceTest {
    private val tenantId = UUID.randomUUID()

    @Test
    fun `creates tenant site with opaque portal ID`() = asTenant {
        val nasId = UUID.randomUUID()
        val repository = InMemorySiteRepository()
        val site = HotspotSiteService(repository, BngApiStub(setOf(nasId)), CatalogApiStub()).create(
            CreateHotspotSiteCommand(nasId, "Lobby", "Jakarta", PortalMode.NETOPS_HOSTED),
        )

        assertThat(site.nasId).isEqualTo(nasId)
        assertThat(site.portalMode).isEqualTo(PortalMode.NETOPS_HOSTED)
        assertThat(site.defaultPlanId).isNull()
        assertThat(site.portalId).matches("^[A-Za-z0-9_-]{22}$")
        assertThat(site.portalId).doesNotContain(tenantId.toString().substring(0, 8))
    }

    @Test
    fun `rejects duplicate NAS binding`() = asTenant {
        val nasId = UUID.randomUUID()
        val repository = InMemorySiteRepository()
        val service = HotspotSiteService(repository, BngApiStub(setOf(nasId)), CatalogApiStub())
        service.create(CreateHotspotSiteCommand(nasId, "Lobby", null, PortalMode.OFF))

        assertThatThrownBy { service.create(CreateHotspotSiteCommand(nasId, "Cafe", null, PortalMode.NAS_OWNED)) }
            .isInstanceOf(ConflictException::class.java)
    }

    @Test
    fun `rejects missing or foreign tenant NAS`() = asTenant {
        val service = HotspotSiteService(InMemorySiteRepository(), BngApiStub(emptySet()), CatalogApiStub())

        assertThatThrownBy { service.create(CreateHotspotSiteCommand(UUID.randomUUID(), "Lobby", null, PortalMode.OFF)) }
            .isInstanceOf(NotFoundException::class.java)
    }

    @Test
    fun `updates site and rejects inactive default plan`() = asTenant {
        val nasId = UUID.randomUUID()
        val validPlanId = UUID.randomUUID()
        val repository = InMemorySiteRepository()
        val service = HotspotSiteService(repository, BngApiStub(setOf(nasId)), CatalogApiStub(setOf(validPlanId)))
        val site = service.create(CreateHotspotSiteCommand(nasId, "Lobby", null, PortalMode.NAS_OWNED, defaultPlanId = validPlanId))

        val updated = service.update(site.id, UpdateHotspotSiteCommand("Cafe", "Bandung", PortalMode.OFF, defaultPlanId = validPlanId))

        assertThat(updated.name).isEqualTo("Cafe")
        assertThat(updated.location).isEqualTo("Bandung")
        assertThat(updated.portalMode).isEqualTo(PortalMode.OFF)
        assertThat(service.list()).containsExactly(updated)
        assertThatThrownBy {
            service.update(site.id, UpdateHotspotSiteCommand("Cafe", null, PortalMode.NAS_OWNED, defaultPlanId = UUID.randomUUID()))
        }.isInstanceOf(NotFoundException::class.java)
    }

    private fun asTenant(block: () -> Unit) = TenantContext.runAs(tenantId, block)

    private class InMemorySiteRepository : HotspotSiteRepository {
        private val sites = mutableMapOf<UUID, HotspotSite>()
        override fun save(site: HotspotSite): HotspotSite = site.also { sites[it.id] = it }
        override fun findAll(): List<HotspotSite> = sites.values.toList()
        override fun findById(id: UUID): HotspotSite? = sites[id]
        override fun findByNasId(nasId: UUID): HotspotSite? = sites.values.firstOrNull { it.nasId == nasId }
        override fun findByPortalId(portalId: String): HotspotSite? = sites.values.firstOrNull { it.portalId == portalId }
        override fun findPublicByPortalId(portalId: String): HotspotSite? = findByPortalId(portalId)
    }

    private class CatalogApiStub(private val activePlanIds: Set<UUID> = emptySet()) : CatalogApi {
        override fun findPlanCommercial(planId: UUID): PlanCommercialRef? = null
        override fun findPlanByName(name: String): PlanCommercialRef? = null
        override fun findPlanNetwork(planId: UUID): PlanNetworkRef? = null
        override fun findActiveHotspotPlan(planId: UUID): PlanNetworkRef? =
            if (planId in activePlanIds) PlanNetworkRef(planId, "Hotspot", 10, 10, "10M/10M", null, false, null, null, null, null, setOf("HOTSPOT")) else null
        override fun findActivePlans(): List<PlanCommercialRef> = emptyList()
    }

    private class BngApiStub(private val nasIds: Set<UUID>) : BngApi {
        override fun hasNas(nasId: UUID): Boolean = nasId in nasIds
        override fun findSubscriberSession(customerId: UUID) = null
        override fun findPppoeByCustomerIds(customerIds: Set<UUID>): Map<UUID, SubscriberPppoeRef> = emptyMap()
        override fun provisionAccess(command: ProvisionAccessSpec): ProvisionedAccessRef = throw UnsupportedOperationException()
        override fun resolveNasForArea(areaId: UUID) = null
        override fun resolveNasByName(name: String) = null
        override fun findAccessByUsername(username: String) = null
        override fun updateAccessFromImport(accessId: UUID, planId: UUID, nasId: UUID?, secret: String?) = Unit
        override fun fetchPppSecretsFromNas(nasId: UUID) = emptyList<PppSecretRef>()
        override fun activeSubscriberLiveness() = emptyList<SubscriberPppoeLiveness>()
        override fun exportAccesses() = emptyList<AccessExportRef>()
    }
}

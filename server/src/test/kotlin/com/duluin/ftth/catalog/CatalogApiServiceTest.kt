package com.duluin.ftth.catalog

import com.duluin.ftth.catalog.application.port.outbound.PlanRepository
import com.duluin.ftth.catalog.application.service.CatalogApiService
import com.duluin.ftth.catalog.domain.model.Plan
import com.duluin.ftth.catalog.domain.model.PlanAttributes
import com.duluin.ftth.catalog.domain.model.ServiceType
import com.duluin.ftth.common.domain.UuidV7
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.util.UUID

class CatalogApiServiceTest {

    @Test
    fun `active HOTSPOT plan resolves the same live BNG network policy`() {
        val plan = plan(serviceTypes = setOf(ServiceType.HOTSPOT), active = true)
        val catalog = CatalogApiService(FakePlanRepository(plan))

        val resolved = catalog.findActiveHotspotPlan(plan.id)

        assertThat(resolved).isEqualTo(catalog.findPlanNetwork(plan.id))
        assertThat(resolved).isNotNull
        assertThat(resolved!!.rateLimit).isEqualTo("2M/10M")
        assertThat(resolved.serviceTypes).containsExactly("HOTSPOT")
    }

    @Test
    fun `non-HOTSPOT plan is not eligible for temporary voucher issuance`() {
        val plan = plan(serviceTypes = setOf(ServiceType.PPPOE), active = true)

        assertThat(CatalogApiService(FakePlanRepository(plan)).findActiveHotspotPlan(plan.id)).isNull()
    }

    @Test
    fun `inactive HOTSPOT plan is not eligible for temporary voucher issuance`() {
        val plan = plan(serviceTypes = setOf(ServiceType.HOTSPOT), active = false)

        assertThat(CatalogApiService(FakePlanRepository(plan)).findActiveHotspotPlan(plan.id)).isNull()
    }

    @Test
    fun `legacy catalog returning only PlanNetworkRef fails closed for hotspot eligibility`() {
        val planId = UuidV7.generate()
        val legacyCatalog = object : CatalogApi {
            override fun findPlanCommercial(planId: UUID): PlanCommercialRef? = null
            override fun findPlanByName(name: String): PlanCommercialRef? = null
            override fun findPlanNetwork(planId: UUID): PlanNetworkRef? = PlanNetworkRef(
                planId = planId,
                name = "Unknown active state",
                downMbps = 10,
                upMbps = 2,
                rateLimit = "2M/10M",
                connectionLimit = null,
                fupEnabled = false,
                fupQuotaMb = null,
                fupRateLimit = null,
                fupDownMbps = null,
                fupUpMbps = null,
                serviceTypes = setOf("HOTSPOT"),
            )
            override fun findActivePlans(): List<PlanCommercialRef> = emptyList()
        }

        assertThat(legacyCatalog.findActiveHotspotPlan(planId)).isNull()
    }

    private fun plan(serviceTypes: Set<ServiceType>, active: Boolean): Plan = Plan.create(
        tenantId = UuidV7.generate(),
        attributes = PlanAttributes(
            name = "Hotspot 10 Mbps",
            description = null,
            price = BigDecimal("50000"),
            downMbps = 10,
            upMbps = 2,
            serviceTypes = serviceTypes,
            active = active,
        ),
    )

    private class FakePlanRepository(private vararg val plans: Plan) : PlanRepository {
        override fun save(plan: Plan): Plan = plan
        override fun findById(id: UUID): Plan? = plans.firstOrNull { it.id == id }
        override fun findAll(): List<Plan> = plans.toList()
        override fun existsByName(name: String): Boolean = plans.any { it.name == name }
        override fun findByNameIgnoreCase(name: String): Plan? = plans.firstOrNull { it.name.equals(name, true) }
    }
}

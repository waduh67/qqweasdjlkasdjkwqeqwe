package com.duluin.ftth.catalog.application.service

import com.duluin.ftth.catalog.CatalogApi
import com.duluin.ftth.catalog.PlanCommercialRef
import com.duluin.ftth.catalog.PlanNetworkRef
import com.duluin.ftth.catalog.application.port.outbound.PlanRepository
import com.duluin.ftth.catalog.domain.model.Plan
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Implementasi kontrak lintas-modul catalog. Membangun pandangan ramping dari agregat
 * `Plan`: sisi komersial untuk snapshot langganan, sisi jaringan (termasuk string
 * Mikrotik-Rate-Limit yang sudah dirakit) untuk penegakan RADIUS.
 */
@Service
@Transactional(readOnly = true)
class CatalogApiService(
    private val planRepository: PlanRepository,
) : CatalogApi {

    override fun findPlanCommercial(planId: UUID): PlanCommercialRef? =
        planRepository.findById(planId)?.toCommercialRef()

    override fun findPlanByName(name: String): PlanCommercialRef? =
        name.trim().takeIf { it.isNotEmpty() }?.let { planRepository.findByNameIgnoreCase(it)?.toCommercialRef() }

    override fun findPlanNetwork(planId: UUID): PlanNetworkRef? =
        planRepository.findById(planId)?.toNetworkRef()

    private fun Plan.toCommercialRef() = PlanCommercialRef(
        planId = id,
        packageName = attributes.name,
        monthlyFee = attributes.price,
        bandwidthMbps = attributes.downMbps,
        active = attributes.active,
        prorateOnActivation = attributes.prorateOnActivation,
        billingDayOfMonth = attributes.billingDayOfMonth,
        dueDays = attributes.dueDays,
        graceDays = attributes.graceDays,
        autoIsolir = attributes.autoIsolir,
    )

    private fun Plan.toNetworkRef() = PlanNetworkRef(
        planId = id,
        name = attributes.name,
        downMbps = attributes.downMbps,
        upMbps = attributes.upMbps,
        rateLimit = rateLimitString(),
        connectionLimit = attributes.connectionLimit,
        fupEnabled = attributes.fupEnabled,
        fupQuotaMb = attributes.fupQuotaMb,
        fupRateLimit = fupRateLimitString(),
        fupDownMbps = attributes.fupDownMbps,
        fupUpMbps = attributes.fupUpMbps,
        serviceTypes = attributes.serviceTypes.mapTo(LinkedHashSet()) { it.name },
    )
}

package com.duluin.ftth.catalog.adapter.outbound.persistence

import com.duluin.ftth.catalog.application.port.outbound.PlanRepository
import com.duluin.ftth.catalog.domain.model.Plan
import com.duluin.ftth.catalog.domain.model.PlanAttributes
import com.duluin.ftth.catalog.domain.model.ServiceType
import com.duluin.ftth.common.tenant.TenantContext
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Adapter persistence paket. Tanpa rahasia untuk dienkripsi. Konversi satu-satunya
 * yang non-trivial: himpunan [ServiceType] ⇄ nama enum digabung koma.
 */
@Component
class PlanPersistenceAdapter(
    private val jpa: PlanJpaRepository,
) : PlanRepository {

    override fun save(plan: Plan): Plan {
        val a = plan.attributes
        val entity = jpa.findById(plan.id).orElse(null)?.apply {
            name = a.name
            description = a.description
            price = a.price
            downMbps = a.downMbps
            upMbps = a.upMbps
            downBurstMbps = a.downBurstMbps
            upBurstMbps = a.upBurstMbps
            downThresholdMbps = a.downThresholdMbps
            upThresholdMbps = a.upThresholdMbps
            burstTimeSec = a.burstTimeSec
            downMinMbps = a.downMinMbps
            upMinMbps = a.upMinMbps
            priority = a.priority
            connectionLimit = a.connectionLimit
            fupEnabled = a.fupEnabled
            fupQuotaMb = a.fupQuotaMb
            fupDownMbps = a.fupDownMbps
            fupUpMbps = a.fupUpMbps
            serviceTypes = a.serviceTypes.joinToString(",") { it.name }
            prorateOnActivation = a.prorateOnActivation
            billingDayOfMonth = a.billingDayOfMonth
            dueDays = a.dueDays
            graceDays = a.graceDays
            autoIsolir = a.autoIsolir
            active = a.active
        } ?: PlanJpaEntity(
            id = plan.id,
            name = a.name,
            description = a.description,
            price = a.price,
            downMbps = a.downMbps,
            upMbps = a.upMbps,
            downBurstMbps = a.downBurstMbps,
            upBurstMbps = a.upBurstMbps,
            downThresholdMbps = a.downThresholdMbps,
            upThresholdMbps = a.upThresholdMbps,
            burstTimeSec = a.burstTimeSec,
            downMinMbps = a.downMinMbps,
            upMinMbps = a.upMinMbps,
            priority = a.priority,
            connectionLimit = a.connectionLimit,
            fupEnabled = a.fupEnabled,
            fupQuotaMb = a.fupQuotaMb,
            fupDownMbps = a.fupDownMbps,
            fupUpMbps = a.fupUpMbps,
            serviceTypes = a.serviceTypes.joinToString(",") { it.name },
            prorateOnActivation = a.prorateOnActivation,
            billingDayOfMonth = a.billingDayOfMonth,
            dueDays = a.dueDays,
            graceDays = a.graceDays,
            autoIsolir = a.autoIsolir,
            active = a.active,
        )
        return jpa.save(entity).toDomain()
    }

    override fun findById(id: UUID): Plan? = jpa.findById(id).orElse(null)?.toDomain()

    override fun findAll(): List<Plan> = jpa.findAllByOrderByNameAsc().map { it.toDomain() }

    override fun existsByName(name: String): Boolean = jpa.existsByName(name)

    private fun PlanJpaEntity.toDomain(): Plan = Plan.rehydrate(
        id = id,
        tenantId = tenantId ?: TenantContext.tenantId(),
        attributes = PlanAttributes(
            name = name,
            description = description,
            price = price,
            downMbps = downMbps,
            upMbps = upMbps,
            downBurstMbps = downBurstMbps,
            upBurstMbps = upBurstMbps,
            downThresholdMbps = downThresholdMbps,
            upThresholdMbps = upThresholdMbps,
            burstTimeSec = burstTimeSec,
            downMinMbps = downMinMbps,
            upMinMbps = upMinMbps,
            priority = priority,
            connectionLimit = connectionLimit,
            fupEnabled = fupEnabled,
            fupQuotaMb = fupQuotaMb,
            fupDownMbps = fupDownMbps,
            fupUpMbps = fupUpMbps,
            serviceTypes = parseServiceTypes(serviceTypes),
            prorateOnActivation = prorateOnActivation,
            billingDayOfMonth = billingDayOfMonth,
            dueDays = dueDays,
            graceDays = graceDays,
            autoIsolir = autoIsolir,
            active = active,
        ),
    )

    private fun parseServiceTypes(joined: String): Set<ServiceType> =
        joined.split(",")
            .mapNotNull { token -> token.trim().takeIf { it.isNotEmpty() }?.let { runCatching { ServiceType.valueOf(it) }.getOrNull() } }
            .toCollection(LinkedHashSet())
}

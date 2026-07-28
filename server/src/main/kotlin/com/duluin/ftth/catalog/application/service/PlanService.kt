package com.duluin.ftth.catalog.application.service

import com.duluin.ftth.catalog.PlanCreated
import com.duluin.ftth.catalog.PlanDeactivated
import com.duluin.ftth.catalog.PlanUpdated
import com.duluin.ftth.catalog.application.port.inbound.ManagePlanUseCase
import com.duluin.ftth.catalog.application.port.inbound.PlanView
import com.duluin.ftth.catalog.application.port.inbound.SavePlanCommand
import com.duluin.ftth.catalog.application.port.outbound.PlanRepository
import com.duluin.ftth.catalog.domain.model.Plan
import com.duluin.ftth.catalog.domain.model.PlanAttributes
import com.duluin.ftth.common.domain.error.ConflictException
import com.duluin.ftth.common.domain.error.NotFoundException
import com.duluin.ftth.common.infrastructure.audit.AuditRecorder
import com.duluin.ftth.common.security.CurrentUserProvider
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
@Transactional
class PlanService(
    private val planRepository: PlanRepository,
    private val currentUser: CurrentUserProvider,
    private val auditor: AuditRecorder,
    private val events: ApplicationEventPublisher,
) : ManagePlanUseCase {

    @Transactional(readOnly = true)
    override fun list(): List<PlanView> = planRepository.findAll().map { it.toView() }

    @Transactional(readOnly = true)
    override fun get(id: UUID): PlanView = require(id).toView()

    override fun create(command: SavePlanCommand): PlanView {
        val name = command.name.trim()
        if (planRepository.existsByName(name)) throw ConflictException("Paket '$name' sudah ada")
        val plan = planRepository.save(
            Plan.create(currentUser.current().tenantId, command.toAttributes()),
        )
        auditor.record("catalog.plan.created", "Plan", plan.id, plan.tenantId, mapOf("name" to plan.name))
        events.publishEvent(PlanCreated(plan.tenantId, plan.id))
        return plan.toView()
    }

    override fun update(id: UUID, command: SavePlanCommand): PlanView {
        val plan = require(id)
        val newName = command.name.trim()
        // Cek keunikan hanya bila nama benar-benar berubah, agar edit field lain tak
        // tertolak oleh namanya sendiri.
        if (newName != plan.name && planRepository.existsByName(newName)) {
            throw ConflictException("Paket '$newName' sudah ada")
        }
        val wasActive = plan.active
        plan.update(command.toAttributes())
        val saved = planRepository.save(plan)
        auditor.record("catalog.plan.updated", "Plan", saved.id, saved.tenantId, mapOf("name" to saved.name))
        events.publishEvent(PlanUpdated(saved.tenantId, saved.id))
        if (wasActive && !saved.active) events.publishEvent(PlanDeactivated(saved.tenantId, saved.id))
        return saved.toView()
    }

    private fun require(id: UUID): Plan =
        planRepository.findById(id) ?: throw NotFoundException("Paket $id tidak ditemukan")
}

private fun SavePlanCommand.toAttributes() = PlanAttributes(
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
    serviceTypes = serviceTypes,
    prorateOnActivation = prorateOnActivation,
    billingDayOfMonth = billingDayOfMonth,
    dueDays = dueDays,
    graceDays = graceDays,
    autoIsolir = autoIsolir,
    active = active,
)

private fun Plan.toView(): PlanView = with(attributes) {
    PlanView(
        id = id,
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
        serviceTypes = serviceTypes.mapTo(LinkedHashSet()) { it.name },
        prorateOnActivation = prorateOnActivation,
        billingDayOfMonth = billingDayOfMonth,
        dueDays = dueDays,
        graceDays = graceDays,
        autoIsolir = autoIsolir,
        active = active,
        rateLimit = rateLimitString(),
        fupRateLimit = fupRateLimitString(),
    )
}

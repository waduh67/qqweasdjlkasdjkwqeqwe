package com.duluin.ftth.incident.adapter.outbound.persistence

import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.incident.application.port.outbound.IncidentRepository
import com.duluin.ftth.incident.domain.model.Incident
import com.duluin.ftth.incident.domain.model.IncidentEvent
import com.duluin.ftth.incident.domain.model.IncidentStatus
import org.springframework.stereotype.Component
import java.util.UUID

@Component
class IncidentPersistenceAdapter(
    private val jpa: IncidentJpaRepository,
    private val eventJpa: IncidentEventJpaRepository,
) : IncidentRepository {

    override fun save(incident: Incident): Incident {
        val entity = jpa.findById(incident.id).orElse(null)?.apply {
            rootLabel = incident.rootLabel
            severity = incident.severity
            status = incident.status
            title = incident.title
            alarmCount = incident.alarmCount
            affectedCustomerCount = incident.affectedCustomerCount
            lastSeenAt = incident.lastSeenAt
            acknowledgedAt = incident.acknowledgedAt
            acknowledgedBy = incident.acknowledgedBy
            resolvedAt = incident.resolvedAt
        } ?: IncidentJpaEntity(
            id = incident.id,
            rootType = incident.rootType,
            rootId = incident.rootId,
            rootLabel = incident.rootLabel,
            severity = incident.severity,
            status = incident.status,
            title = incident.title,
            alarmCount = incident.alarmCount,
            affectedCustomerCount = incident.affectedCustomerCount,
            openedAt = incident.openedAt,
            lastSeenAt = incident.lastSeenAt,
            acknowledgedAt = incident.acknowledgedAt,
            acknowledgedBy = incident.acknowledgedBy,
            resolvedAt = incident.resolvedAt,
        )
        val saved = jpa.save(entity)

        // Simpan event timeline yang tertunda, lalu kosongkan agar tidak tersimpan ganda.
        incident.pendingEvents().forEach { ev ->
            eventJpa.save(
                IncidentEventJpaEntity(
                    id = ev.id,
                    incidentId = ev.incidentId,
                    type = ev.type,
                    message = ev.message,
                    actorId = ev.actorId,
                    at = ev.at,
                ),
            )
        }
        incident.clearPending()
        return saved.toDomain()
    }

    override fun findById(id: UUID): Incident? = jpa.findById(id).orElse(null)?.toDomain()

    override fun findOpen(): List<Incident> =
        jpa.findByStatusNot(IncidentStatus.RESOLVED)
            .map { it.toDomain() }
            // Enum @Enumerated(STRING) tidak bisa diurut menurut keparahan di SQL
            // (CRITICAL < INFO secara alfabet), jadi diurut di sini menurut ordinal.
            .sortedWith(compareByDescending<Incident> { it.severity.ordinal }.thenByDescending { it.openedAt })

    override fun timelineOf(incidentId: UUID): List<IncidentEvent> =
        eventJpa.findByIncidentIdOrderByAt(incidentId).map { it.toDomain() }
}

private fun IncidentJpaEntity.toDomain(): Incident = Incident.rehydrate(
    id = id,
    tenantId = tenantId ?: TenantContext.tenantId(),
    rootType = rootType,
    rootId = rootId,
    rootLabel = rootLabel,
    severity = severity,
    status = status,
    title = title,
    alarmCount = alarmCount,
    affectedCustomerCount = affectedCustomerCount,
    openedAt = openedAt,
    lastSeenAt = lastSeenAt,
    acknowledgedAt = acknowledgedAt,
    acknowledgedBy = acknowledgedBy,
    resolvedAt = resolvedAt,
)

private fun IncidentEventJpaEntity.toDomain(): IncidentEvent = IncidentEvent.rehydrate(
    id = id,
    tenantId = tenantId ?: TenantContext.tenantId(),
    incidentId = incidentId,
    type = type,
    message = message,
    actorId = actorId,
    at = at,
)

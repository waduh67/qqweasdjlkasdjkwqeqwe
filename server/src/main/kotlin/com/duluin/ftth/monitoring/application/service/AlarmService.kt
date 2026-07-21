package com.duluin.ftth.monitoring.application.service

import com.duluin.ftth.common.domain.Page
import com.duluin.ftth.common.domain.PageRequest
import com.duluin.ftth.common.domain.error.NotFoundException
import com.duluin.ftth.common.infrastructure.audit.AuditRecorder
import com.duluin.ftth.common.security.CurrentUserProvider
import com.duluin.ftth.monitoring.application.port.inbound.AlarmQuery
import com.duluin.ftth.monitoring.application.port.inbound.AlarmSummary
import com.duluin.ftth.monitoring.application.port.inbound.AlarmView
import com.duluin.ftth.monitoring.application.port.outbound.AlarmRepository
import com.duluin.ftth.monitoring.domain.model.Alarm
import com.duluin.ftth.monitoring.domain.model.AlarmKind
import com.duluin.ftth.monitoring.domain.model.AlarmSeverity
import com.duluin.ftth.monitoring.domain.model.AlarmStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

@Service
@Transactional
class AlarmService(
    private val alarmRepository: AlarmRepository,
    private val currentUser: CurrentUserProvider,
    private val auditor: AuditRecorder,
) : AlarmQuery {

    @Transactional(readOnly = true)
    override fun search(status: AlarmStatus?, kind: AlarmKind?, pageRequest: PageRequest): Page<AlarmView> =
        alarmRepository.search(status, kind, pageRequest).map { it.toView() }

    @Transactional(readOnly = true)
    override fun summary(): AlarmSummary = buildSummary()

    override fun acknowledge(id: UUID): AlarmView {
        val alarm = require(id)
        alarm.acknowledge(currentUser.current().userId)
        val saved = alarmRepository.save(alarm)
        auditor.record(
            "alarm.acknowledged", "Alarm", saved.id, saved.tenantId,
            mapOf("kind" to saved.kind.name, "entity" to saved.entityLabel),
        )
        return saved.toView()
    }

    /**
     * Penutupan manual, untuk kondisi yang tidak akan menutup sendiri — misalnya
     * ONU yang dicabut permanen sehingga tidak akan pernah ada bacaan pemulihnya.
     */
    override fun clear(id: UUID): AlarmView {
        val alarm = require(id)
        alarm.clear()
        val saved = alarmRepository.save(alarm)
        auditor.record(
            "alarm.cleared", "Alarm", saved.id, saved.tenantId,
            mapOf("kind" to saved.kind.name, "entity" to saved.entityLabel),
        )
        return saved.toView()
    }

    internal fun buildSummary(): AlarmSummary {
        val counts = alarmRepository.countByStatus()
        val open = alarmRepository.search(
            AlarmStatus.ACTIVE, null, PageRequest(0, MAX_SEVERITY_SAMPLE),
        ).content
        return AlarmSummary(
            active = counts[AlarmStatus.ACTIVE] ?: 0,
            acknowledged = counts[AlarmStatus.ACKNOWLEDGED] ?: 0,
            cleared = counts[AlarmStatus.CLEARED] ?: 0,
            bySeverity = AlarmSeverity.entries.associateWith { severity ->
                open.count { it.severity == severity }.toLong()
            },
        )
    }

    private fun require(id: UUID): Alarm =
        alarmRepository.findById(id) ?: throw NotFoundException("Alarm $id tidak ditemukan")

    private companion object {
        /** Cukup untuk memberi gambaran sebaran keparahan tanpa memuat semuanya. */
        const val MAX_SEVERITY_SAMPLE = 200
    }
}

internal fun Alarm.toView(now: Instant = Instant.now()) = AlarmView(
    id = id,
    kind = kind,
    kindDescription = kind.description,
    severity = severity,
    status = status,
    entityType = entityType,
    entityId = entityId,
    entityLabel = entityLabel,
    message = message,
    measuredValue = measuredValue,
    raisedAt = raisedAt,
    lastSeenAt = lastSeenAt,
    clearedAt = clearedAt,
    acknowledgedAt = acknowledgedAt,
    occurrenceCount = occurrenceCount,
    openMinutes = openDuration(now).toMinutes(),
)

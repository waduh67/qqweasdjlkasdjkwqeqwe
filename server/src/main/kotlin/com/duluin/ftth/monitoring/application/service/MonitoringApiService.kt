package com.duluin.ftth.monitoring.application.service

import com.duluin.ftth.monitoring.AlarmImpact
import com.duluin.ftth.monitoring.MonitoringApi
import com.duluin.ftth.monitoring.OnuLiveMetric
import com.duluin.ftth.monitoring.application.port.outbound.AlarmRepository
import com.duluin.ftth.monitoring.application.port.outbound.OnuMetricRepository
import com.duluin.ftth.monitoring.domain.model.AlarmEntityType
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
@Transactional(readOnly = true)
class MonitoringApiService(
    private val alarmRepository: AlarmRepository,
    private val metricRepository: OnuMetricRepository,
) : MonitoringApi {

    override fun activeImpacts(): List<AlarmImpact> {
        val open = alarmRepository.findAllOpen()

        // Sebab putus terakhir hanya relevan untuk ONU dan diambil sekali untuk
        // seluruh ONU beralarm (satu query DISTINCT ON), bukan per-alarm. Inilah
        // yang membiarkan korelasi insiden membedakan "area mati listrik" dari
        // "fiber putus" — lihat IncidentCorrelationService.
        val onuIds = open.filter { it.entityType == AlarmEntityType.ONU }.mapTo(HashSet()) { it.entityId }
        val downCauseByOnu = if (onuIds.isEmpty()) emptyMap()
        else metricRepository.findLatestByOnuIds(onuIds).mapValues { it.value.downCause }

        return open.map {
            AlarmImpact(
                entityType = it.entityType.name,
                entityId = it.entityId,
                severity = it.severity.name,
                kind = it.kind.name,
                label = it.entityLabel,
                downCause = if (it.entityType == AlarmEntityType.ONU) downCauseByOnu[it.entityId] else null,
            )
        }
    }

    override fun latestMetricsByOnuIds(onuIds: Set<UUID>): Map<UUID, OnuLiveMetric> {
        if (onuIds.isEmpty()) return emptyMap()
        return metricRepository.findLatestByOnuIds(onuIds).mapValues { (onuId, point) ->
            OnuLiveMetric(
                onuId = onuId,
                status = point.status,
                rxPowerDbm = point.rxPowerDbm,
                distanceMeters = point.distanceMeters,
                downCause = point.downCause,
                lastOffAt = point.lastOffAt,
                lastOnAt = point.lastOnAt,
            )
        }
    }
}

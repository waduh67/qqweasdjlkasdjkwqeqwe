package com.duluin.ftth.monitoring.application.service

import com.duluin.ftth.monitoring.AlarmImpact
import com.duluin.ftth.monitoring.MonitoringApi
import com.duluin.ftth.monitoring.application.port.outbound.AlarmRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional(readOnly = true)
class MonitoringApiService(
    private val alarmRepository: AlarmRepository,
) : MonitoringApi {

    override fun activeImpacts(): List<AlarmImpact> =
        alarmRepository.findAllOpen().map {
            AlarmImpact(entityType = it.entityType.name, entityId = it.entityId, severity = it.severity.name)
        }
}

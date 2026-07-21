package com.duluin.ftth.monitoring.application.service

import com.duluin.ftth.common.domain.PageRequest
import com.duluin.ftth.common.domain.error.ValidationException
import com.duluin.ftth.common.security.CurrentUserProvider
import com.duluin.ftth.customer.CustomerApi
import com.duluin.ftth.monitoring.application.port.inbound.AlarmQuery
import com.duluin.ftth.monitoring.application.port.inbound.HistoryPoint
import com.duluin.ftth.monitoring.application.port.inbound.MetricQuery
import com.duluin.ftth.monitoring.application.port.inbound.MonitoringDashboard
import com.duluin.ftth.monitoring.application.port.inbound.OnuHistoryView
import com.duluin.ftth.monitoring.application.port.inbound.OnuMetricView
import com.duluin.ftth.monitoring.application.port.outbound.CollectorRepository
import com.duluin.ftth.monitoring.application.port.outbound.OnuMetricRepository
import com.duluin.ftth.monitoring.domain.model.OpticalTrend
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant
import java.util.UUID

@Service
@Transactional(readOnly = true)
class MetricQueryService(
    private val metricRepository: OnuMetricRepository,
    private val collectorRepository: CollectorRepository,
    private val customerApi: CustomerApi,
    private val alarmQuery: AlarmQuery,
    private val alarmService: AlarmService,
    private val currentUser: CurrentUserProvider,
) : MetricQuery {

    override fun latestForCustomer(customerId: UUID): List<OnuMetricView> {
        val placement = customerApi.findPlacementOf(customerId)
        val serials = listOfNotNull(placement?.onuSerialNumber)
        if (serials.isEmpty()) return emptyList()

        val onus = customerApi.findOnusBySerialNumbers(serials.toSet())
        val latest = metricRepository.findLatestByOnuIds(onus.mapTo(HashSet()) { it.id })
        return onus.mapNotNull { onu ->
            latest[onu.id]?.let { point ->
                OnuMetricView(
                    onuId = onu.id,
                    serialNumber = onu.serialNumber,
                    time = point.time,
                    status = point.status,
                    rxPowerDbm = point.rxPowerDbm,
                    txPowerDbm = point.txPowerDbm,
                    distanceMeters = point.distanceMeters,
                )
            }
        }
    }

    override fun history(onuId: UUID, hours: Int): OnuHistoryView {
        if (hours !in 1..MAX_HISTORY_HOURS) {
            throw ValidationException("Rentang riwayat harus 1-$MAX_HISTORY_HOURS jam")
        }
        val until = Instant.now()
        val since = until.minus(Duration.ofHours(hours.toLong()))

        val points = metricRepository.findHistory(onuId, since, until)
        val trend = metricRepository.computeTrend(onuId, since)

        return OnuHistoryView(
            onuId = onuId,
            points = points.map { HistoryPoint(it.time, it.rxPowerDbm, it.status) },
            averageRxPowerDbm = trend?.averageRxPowerDbm,
            minRxPowerDbm = trend?.minRxPowerDbm,
            maxRxPowerDbm = trend?.maxRxPowerDbm,
            trendDbPerDay = trend?.trendDbPerDay,
            degrading = trend?.degrading ?: false,
        )
    }

    override fun dashboard(): MonitoringDashboard {
        val collectors = collectorRepository.findAllByTenant(currentUser.current().tenantId)
        return MonitoringDashboard(
            collectors = collectors.size,
            collectorsSilent = collectors.count { it.isSilent() },
            metricsLast24h = metricRepository.countSince(Instant.now().minus(Duration.ofHours(24))),
            alarms = alarmService.buildSummary(),
            recentAlarms = alarmQuery
                .search(null, null, PageRequest(0, RECENT_ALARM_LIMIT, sort = "raisedAt", descending = true))
                .content,
        )
    }

    private companion object {
        /** Batas atas rentang; data mentah memang hanya disimpan 90 hari. */
        const val MAX_HISTORY_HOURS = 24 * 90
        const val RECENT_ALARM_LIMIT = 10
    }
}

/** Dipakai uji ambang degradasi tanpa menyentuh basis data. */
internal fun OpticalTrend.describeForLog(): String =
    "onu=$onuId samples=$samples trend=${trendDbPerDay ?: "-"} dB/hari degrading=$degrading"

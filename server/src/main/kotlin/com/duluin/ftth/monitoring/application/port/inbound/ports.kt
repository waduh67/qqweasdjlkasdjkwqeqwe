package com.duluin.ftth.monitoring.application.port.inbound

import com.duluin.ftth.common.domain.Page
import com.duluin.ftth.common.domain.PageRequest
import com.duluin.ftth.monitoring.domain.model.AlarmEntityType
import com.duluin.ftth.monitoring.domain.model.AlarmKind
import com.duluin.ftth.monitoring.domain.model.AlarmSeverity
import com.duluin.ftth.monitoring.domain.model.AlarmStatus
import com.duluin.ftth.monitoring.domain.model.CollectorStatus
import java.time.Instant
import java.util.UUID

interface ManageCollectorUseCase {

    fun list(): List<CollectorView>

    fun get(id: UUID): CollectorView

    /**
     * Membuat collector baru. API key mentah hanya ada di respons ini dan tidak
     * bisa diambil lagi — hanya hash-nya yang tersimpan.
     */
    fun create(command: SaveCollectorCommand): CollectorCreated

    fun update(id: UUID, command: SaveCollectorCommand): CollectorView

    /** Menugaskan OLT mana yang dipolling. Set kosong berarti seluruh OLT tenant. */
    fun assignOlts(id: UUID, oltIds: Set<UUID>): CollectorView

    fun delete(id: UUID)
}

data class SaveCollectorCommand(
    val name: String,
    val pollIntervalSeconds: Int,
    val status: CollectorStatus = CollectorStatus.ACTIVE,
)

data class CollectorView(
    val id: UUID,
    val name: String,
    val status: CollectorStatus,
    val pollIntervalSeconds: Int,
    val apiKeyHint: String,
    val agentVersion: String?,
    val lastSeenAt: Instant?,
    val lastCycleSummary: String?,
    /** Melewatkan beberapa siklus berturut-turut — collector kemungkinan mati. */
    val silent: Boolean,
    val assignedOltIds: Set<UUID>,
)

data class CollectorCreated(val collector: CollectorView, val apiKey: String)

interface AlarmQuery {

    fun search(status: AlarmStatus?, kind: AlarmKind?, pageRequest: PageRequest): Page<AlarmView>

    fun summary(): AlarmSummary

    fun acknowledge(id: UUID): AlarmView

    fun clear(id: UUID): AlarmView
}

data class AlarmView(
    val id: UUID,
    val kind: AlarmKind,
    val kindDescription: String,
    val severity: AlarmSeverity,
    val status: AlarmStatus,
    val entityType: AlarmEntityType,
    val entityId: UUID,
    val entityLabel: String,
    val message: String,
    val measuredValue: Double?,
    val raisedAt: Instant,
    val lastSeenAt: Instant,
    val clearedAt: Instant?,
    val acknowledgedAt: Instant?,
    val occurrenceCount: Int,
    val openMinutes: Long,
)

data class AlarmSummary(
    val active: Long,
    val acknowledged: Long,
    val cleared: Long,
    val bySeverity: Map<AlarmSeverity, Long>,
)

interface MetricQuery {

    /** Bacaan terakhir setiap ONU milik seorang pelanggan. */
    fun latestForCustomer(customerId: UUID): List<OnuMetricView>

    /** Riwayat redaman satu ONU untuk digambar sebagai grafik. */
    fun history(onuId: UUID, hours: Int): OnuHistoryView

    fun dashboard(): MonitoringDashboard
}

data class OnuMetricView(
    val onuId: UUID,
    val serialNumber: String,
    val time: Instant,
    val status: String,
    val rxPowerDbm: Double?,
    val txPowerDbm: Double?,
    val distanceMeters: Int?,
    /**
     * Sebab gangguan terakhir ONU (mis. DYING_GASP, LOS) — memisahkan "pelanggan
     * mati listrik" dari "fiber putus" saat status sama-sama menunjukkan mati.
     * `null` bila OLT tidak melaporkannya.
     */
    val downCause: String?,
    /**
     * Kapan ONU terakhir putus dan terakhir kembali online menurut register OLT.
     * Menjawab "sejak kapan mati" dan "sudah berapa lama pulih" tanpa menunggu
     * siklus polling berikutnya. `null` bila OLT tidak melaporkannya.
     */
    val lastOffAt: Instant?,
    val lastOnAt: Instant?,
)

data class OnuHistoryView(
    val onuId: UUID,
    val points: List<HistoryPoint>,
    val averageRxPowerDbm: Double?,
    val minRxPowerDbm: Double?,
    val maxRxPowerDbm: Double?,
    val trendDbPerDay: Double?,
    /** Redaman menurun cukup cepat untuk layak dijadwalkan pemeliharaan. */
    val degrading: Boolean,
)

data class HistoryPoint(
    val time: Instant,
    val rxPowerDbm: Double?,
    val status: String,
)

data class MonitoringDashboard(
    val collectors: Int,
    val collectorsSilent: Int,
    val metricsLast24h: Long,
    val alarms: AlarmSummary,
    val recentAlarms: List<AlarmView>,
)

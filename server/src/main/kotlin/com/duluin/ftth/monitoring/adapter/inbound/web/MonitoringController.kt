package com.duluin.ftth.monitoring.adapter.inbound.web

import com.duluin.ftth.common.domain.PageRequest
import com.duluin.ftth.common.infrastructure.web.PageResponse
import com.duluin.ftth.monitoring.application.port.inbound.AlarmQuery
import com.duluin.ftth.monitoring.application.port.inbound.AlarmView
import com.duluin.ftth.monitoring.application.port.inbound.CollectorCreated
import com.duluin.ftth.monitoring.application.port.inbound.CollectorView
import com.duluin.ftth.monitoring.application.port.inbound.ManageCollectorUseCase
import com.duluin.ftth.monitoring.application.port.inbound.MetricQuery
import com.duluin.ftth.monitoring.application.port.inbound.MonitoringDashboard
import com.duluin.ftth.monitoring.application.port.inbound.OnuHistoryView
import com.duluin.ftth.monitoring.application.port.inbound.OnuMetricView
import com.duluin.ftth.monitoring.application.port.inbound.SaveCollectorCommand
import com.duluin.ftth.monitoring.domain.model.AlarmKind
import com.duluin.ftth.monitoring.domain.model.AlarmStatus
import com.duluin.ftth.monitoring.domain.model.CollectorStatus
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/monitoring")
@Tag(name = "Monitoring")
@SecurityRequirement(name = "bearer-jwt")
class MonitoringController(
    private val manageCollector: ManageCollectorUseCase,
    private val alarmQuery: AlarmQuery,
    private val metricQuery: MetricQuery,
) {
    @GetMapping("/dashboard")
    @PreAuthorize("@authz.can('monitoring.dashboard.view')")
    fun dashboard(): MonitoringDashboard = metricQuery.dashboard()

    // ---- Collector ----

    @GetMapping("/collectors")
    @PreAuthorize("@authz.can('monitoring.collector.view')")
    fun listCollectors(): List<CollectorView> = manageCollector.list()

    /**
     * Respons memuat API key mentah — SATU-SATUNYA kali kunci itu terlihat.
     * Setelah ini hanya hash-nya yang tersimpan dan tidak bisa dipulihkan.
     */
    @PostMapping("/collectors")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@authz.can('monitoring.collector.manage')")
    @Operation(summary = "Buat collector baru; API key hanya ditampilkan sekali di sini")
    fun createCollector(@Valid @RequestBody request: CollectorRequest): CollectorCreated =
        manageCollector.create(SaveCollectorCommand(request.name, request.pollIntervalSeconds, request.status))

    @PutMapping("/collectors/{id}")
    @PreAuthorize("@authz.can('monitoring.collector.manage')")
    fun updateCollector(@PathVariable id: UUID, @Valid @RequestBody request: CollectorRequest): CollectorView =
        manageCollector.update(id, SaveCollectorCommand(request.name, request.pollIntervalSeconds, request.status))

    @PutMapping("/collectors/{id}/olts")
    @PreAuthorize("@authz.can('monitoring.collector.manage')")
    @Operation(summary = "Tugaskan OLT ke collector; daftar kosong berarti seluruh OLT tenant")
    fun assignOlts(@PathVariable id: UUID, @RequestBody request: AssignOltsRequest): CollectorView =
        manageCollector.assignOlts(id, request.oltIds)

    @DeleteMapping("/collectors/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("@authz.can('monitoring.collector.manage')")
    fun deleteCollector(@PathVariable id: UUID) = manageCollector.delete(id)

    // ---- Alarm ----

    @GetMapping("/alarms")
    @PreAuthorize("@authz.can('monitoring.alarm.view')")
    fun listAlarms(
        @RequestParam(required = false) status: AlarmStatus?,
        @RequestParam(required = false) kind: AlarmKind?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
    ): PageResponse<AlarmView> = PageResponse.from(
        alarmQuery.search(status, kind, PageRequest(page, size, sort = "raisedAt", descending = true)),
    )

    @PostMapping("/alarms/{id}/acknowledge")
    @PreAuthorize("@authz.can('monitoring.alarm.ack')")
    fun acknowledgeAlarm(@PathVariable id: UUID): AlarmView = alarmQuery.acknowledge(id)

    @PostMapping("/alarms/{id}/clear")
    @PreAuthorize("@authz.can('monitoring.alarm.ack')")
    fun clearAlarm(@PathVariable id: UUID): AlarmView = alarmQuery.clear(id)

    // ---- Metrik ----

    @GetMapping("/customers/{customerId}/metrics")
    @PreAuthorize("@authz.can('monitoring.metric.view')")
    fun latestForCustomer(@PathVariable customerId: UUID): List<OnuMetricView> =
        metricQuery.latestForCustomer(customerId)

    @GetMapping("/onus/{onuId}/history")
    @PreAuthorize("@authz.can('monitoring.metric.view')")
    @Operation(summary = "Riwayat redaman ONU beserta kecenderungannya (dB/hari)")
    fun history(
        @PathVariable onuId: UUID,
        @RequestParam(defaultValue = "24") hours: Int,
    ): OnuHistoryView = metricQuery.history(onuId, hours)
}

data class CollectorRequest(
    @field:NotBlank @field:Size(max = 150) val name: String,
    @field:Min(30) @field:Max(86_400) val pollIntervalSeconds: Int = 300,
    val status: CollectorStatus = CollectorStatus.ACTIVE,
)

data class AssignOltsRequest(val oltIds: Set<UUID> = emptySet())

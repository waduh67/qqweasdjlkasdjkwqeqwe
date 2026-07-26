package com.duluin.ftth.monitoring.adapter.inbound.web

import com.duluin.ftth.contract.BngIngestResult
import com.duluin.ftth.contract.BngSessionBatch
import com.duluin.ftth.contract.CollectorConfig
import com.duluin.ftth.contract.CollectorHeartbeat
import com.duluin.ftth.contract.IngestResult
import com.duluin.ftth.contract.MetricBatch
import com.duluin.ftth.monitoring.adapter.inbound.security.CollectorPrincipal
import com.duluin.ftth.monitoring.application.service.CollectorGatewayService
import com.duluin.ftth.monitoring.application.service.MetricIngestionService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Endpoint yang dipakai agent collector.
 *
 * Autentikasinya API key, ditangani `CollectorApiKeyFilter` pada rantai keamanan
 * terpisah — tidak ada `@PreAuthorize` di sini karena collector memang tidak
 * punya izin RBAC. Tenant sudah terpasang filter sebelum method ini berjalan.
 *
 * Permukaannya sengaja hanya menulis: collector tidak pernah bisa membaca data
 * pelanggan, sehingga API key yang bocor pun tidak membuka isi tenant.
 */
@RestController
@RequestMapping("/api/collector")
@Tag(name = "Collector Gateway")
class CollectorGatewayController(
    private val gateway: CollectorGatewayService,
    private val ingestion: MetricIngestionService,
) {
    @PostMapping("/heartbeat")
    @Operation(summary = "Melapor hidup dan mengambil konfigurasi polling terbaru")
    fun heartbeat(
        @AuthenticationPrincipal principal: CollectorPrincipal,
        @RequestBody heartbeat: CollectorHeartbeat,
    ): CollectorConfig = gateway.handleHeartbeat(principal.collectorId, heartbeat)

    @PostMapping("/metrics")
    @Operation(summary = "Mengirim batch hasil polling ONU")
    fun ingest(
        @AuthenticationPrincipal principal: CollectorPrincipal,
        @RequestBody batch: MetricBatch,
    ): IngestResult = ingestion.ingest(principal.collectorId, principal.tenantId, batch)

    @PostMapping("/bng-sessions")
    @Operation(summary = "Mengirim batch sesi PPPoE dari sebuah BRAS")
    fun ingestBngSessions(
        @AuthenticationPrincipal principal: CollectorPrincipal,
        @RequestBody batch: BngSessionBatch,
    ): BngIngestResult = gateway.handleBngSessions(principal.collectorId, principal.tenantId, batch)
}

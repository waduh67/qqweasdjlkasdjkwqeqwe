package com.duluin.ftth.inventory.adapter.inbound.web

import com.duluin.ftth.inventory.application.service.WarehouseReportService
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.util.MultiValueMap
import org.springframework.web.bind.annotation.*
import java.util.UUID

@RestController
@RequestMapping("/api/v1/warehouse/reports")
class WarehouseReportController(private val reports: WarehouseReportService) {
    @GetMapping("/{kind}")
    fun report(@PathVariable kind: String, @RequestParam parameters: MultiValueMap<String, String>) = json(reports.report(kind, parameters))

    @GetMapping("/{kind}/export.csv")
    fun export(@PathVariable kind: String, @RequestParam parameters: MultiValueMap<String, String>) = ResponseEntity.ok()
        .contentType(MediaType.parseMediaType("text/csv;charset=UTF-8")).header("Cache-Control", "no-store")
        .header("Content-Disposition", "attachment; filename=warehouse-report.csv").body(reports.report(kind, parameters, true))

    @GetMapping("/serial-chain/{assetId}")
    fun serialChain(@PathVariable assetId: UUID, @RequestParam parameters: MultiValueMap<String, String>) = json(reports.serialChain(assetId, parameters))

    @GetMapping("/documents/{documentId}/revisions/{revision}/print")
    fun print(@PathVariable documentId: UUID, @PathVariable revision: Long) = json(reports.print(documentId, revision))

    private fun json(body: String) = ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).header("Cache-Control", "no-store").body(body)
}

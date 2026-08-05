package com.duluin.ftth.reporting.adapter.inbound.web

import com.duluin.ftth.reporting.application.port.inbound.ReportOverview
import com.duluin.ftth.reporting.application.port.inbound.ReportQuery
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate

/**
 * Endpoint laporan & analitik tenant. Di-anchor pada satu izin `report.view` (pandangan
 * manajerial menyeluruh). Rentang default = bulan berjalan sampai hari ini; tren default 6
 * bulan terakhir — dipilih server agar klien tak perlu tahu "hari ini" tenant.
 */
@RestController
@RequestMapping("/api/reports")
@Tag(name = "Reporting")
@SecurityRequirement(name = "bearer-jwt")
class ReportController(
    private val query: ReportQuery,
) {

    @GetMapping("/overview")
    @PreAuthorize("@authz.can('report.view')")
    fun overview(
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) from: LocalDate?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) to: LocalDate?,
        @RequestParam(required = false, defaultValue = "6") trailingMonths: Int,
    ): ReportOverview {
        val end = to ?: LocalDate.now()
        val start = from ?: end.withDayOfMonth(1)
        return query.overview(start, end, trailingMonths)
    }
}

package com.duluin.ftth.audit.adapter.inbound.web

import com.duluin.ftth.audit.application.port.inbound.AuditEntryView
import com.duluin.ftth.audit.application.port.inbound.AuditQuery
import com.duluin.ftth.common.domain.PageRequest
import com.duluin.ftth.common.infrastructure.web.PageResponse
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/audit-logs")
@Tag(name = "Audit")
@SecurityRequirement(name = "bearer-jwt")
class AuditController(
    private val auditQuery: AuditQuery,
) {
    @GetMapping
    @PreAuthorize("@authz.can('audit.log.view')")
    fun list(
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
    ): PageResponse<AuditEntryView> =
        PageResponse.from(auditQuery.list(PageRequest(page, size, sort = "occurredAt", descending = true)))
}

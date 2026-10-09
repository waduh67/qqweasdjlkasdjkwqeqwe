package com.duluin.ftth.iam.adapter.inbound.web

import com.duluin.ftth.common.domain.PageRequest
import com.duluin.ftth.common.domain.error.ValidationException
import com.duluin.ftth.common.infrastructure.web.PageResponse
import com.duluin.ftth.iam.application.port.outbound.TechnicianChoice
import com.duluin.ftth.iam.application.service.TechnicianDirectoryService
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/technicians")
class TechnicianDirectoryController(private val directory: TechnicianDirectoryService) {
    @GetMapping
    @PreAuthorize("@authz.can('warehouse.request.review') or @authz.can('warehouse.technician.manage') or " +
        "@authz.can('workorder.order.create') or @authz.can('workorder.order.update') or @authz.can('workorder.order.assign')")
    fun list(
        @RequestParam(required = false) query: String?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "25") size: Int,
        @RequestParam(defaultValue = "false") pureOnly: Boolean,
    ): PageResponse<TechnicianChoice> {
        if (page < 0 || size !in 1..200) throw ValidationException("Halaman dan ukuran daftar teknisi tidak valid")
        return PageResponse.from(directory.search(query, PageRequest(page, size), pureOnly))
    }
}

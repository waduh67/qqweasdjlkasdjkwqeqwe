package com.duluin.ftth.workorder.adapter.inbound.web

import com.duluin.ftth.common.domain.PageRequest
import com.duluin.ftth.common.domain.error.ValidationException
import com.duluin.ftth.common.infrastructure.web.PageResponse
import com.duluin.ftth.workorder.application.service.ReferenceWorkAreaService
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/v2/work-orders/areas")
@PreAuthorize("@authz.can('workorder.order.create') or @authz.can('workorder.order.update')")
class ReferenceWorkAreaController(private val areas: ReferenceWorkAreaService) {
    @GetMapping
    fun list(@RequestParam(required = false) query: String?, @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "25") size: Int) =
        if (page < 0 || size !in 1..200) throw ValidationException("Halaman dan ukuran area tidak valid")
        else PageResponse.from(areas.search(query, PageRequest(page, size)))

    @GetMapping("/{id}")
    fun get(@PathVariable id: UUID) = areas.get(id)
}

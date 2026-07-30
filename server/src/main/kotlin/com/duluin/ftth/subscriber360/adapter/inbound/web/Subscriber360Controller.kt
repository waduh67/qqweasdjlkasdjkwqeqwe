package com.duluin.ftth.subscriber360.adapter.inbound.web

import com.duluin.ftth.subscriber360.application.port.inbound.Subscriber360Query
import com.duluin.ftth.subscriber360.application.port.inbound.Subscriber360View
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * Satu endpoint agregat untuk pandangan 360° pelanggan — menggantikan fan-out klien di
 * `CustomerDetailPage`. Di-anchor pada `customer.customer.view`; facet lintas-modul
 * digating izinnya masing-masing di dalam [Subscriber360Query].
 */
@RestController
@RequestMapping("/api/subscriber-360")
@Tag(name = "Subscriber 360")
@SecurityRequirement(name = "bearer-jwt")
class Subscriber360Controller(
    private val query: Subscriber360Query,
) {

    @GetMapping("/{customerId}")
    @PreAuthorize("@authz.can('customer.customer.view')")
    fun assemble(@PathVariable customerId: UUID): Subscriber360View = query.assemble(customerId)
}

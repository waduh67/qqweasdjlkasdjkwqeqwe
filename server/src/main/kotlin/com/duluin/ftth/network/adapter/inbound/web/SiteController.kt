package com.duluin.ftth.network.adapter.inbound.web

import com.duluin.ftth.common.domain.PageRequest
import com.duluin.ftth.common.infrastructure.web.PageResponse
import com.duluin.ftth.network.application.port.inbound.ManageSiteUseCase
import com.duluin.ftth.network.application.port.inbound.SaveSiteCommand
import com.duluin.ftth.network.application.port.inbound.SiteView
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
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
@RequestMapping("/api/sites")
@Tag(name = "Network — Sites")
@SecurityRequirement(name = "bearer-jwt")
class SiteController(
    private val manageSite: ManageSiteUseCase,
) {
    @GetMapping
    @PreAuthorize("@authz.can('network.site.view')")
    fun list(
        @RequestParam(required = false) query: String?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
    ): PageResponse<SiteView> =
        PageResponse.from(manageSite.search(query.orEmpty(), PageRequest(page, size, sort = "code")))

    @GetMapping("/{id}")
    @PreAuthorize("@authz.can('network.site.view')")
    fun get(@PathVariable id: UUID): SiteView = manageSite.get(id)

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@authz.can('network.site.create')")
    fun create(@Valid @RequestBody request: SiteRequest): SiteView = manageSite.create(request.toCommand())

    @PutMapping("/{id}")
    @PreAuthorize("@authz.can('network.site.update')")
    fun update(@PathVariable id: UUID, @Valid @RequestBody request: SiteRequest): SiteView =
        manageSite.update(id, request.toCommand())

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("@authz.can('network.site.delete')")
    fun delete(@PathVariable id: UUID) = manageSite.delete(id)
}

private fun SiteRequest.toCommand() = SaveSiteCommand(
    code = code,
    name = name,
    address = address,
    location = location.toCoordinate(),
    areaId = areaId,
)

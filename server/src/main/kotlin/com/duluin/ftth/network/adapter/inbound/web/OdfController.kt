package com.duluin.ftth.network.adapter.inbound.web

import com.duluin.ftth.common.domain.PageRequest
import com.duluin.ftth.common.infrastructure.web.PageResponse
import com.duluin.ftth.network.application.port.inbound.ManageOdfUseCase
import com.duluin.ftth.network.application.port.inbound.OdfView
import com.duluin.ftth.network.application.port.inbound.SaveOdfCommand
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
@RequestMapping("/api/odfs")
@Tag(name = "Network — ODF")
@SecurityRequirement(name = "bearer-jwt")
class OdfController(
    private val manageOdf: ManageOdfUseCase,
) {
    @GetMapping
    @PreAuthorize("@authz.can('network.odf.view')")
    fun list(
        @RequestParam(required = false) query: String?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
    ): PageResponse<OdfView> =
        PageResponse.from(manageOdf.search(query.orEmpty(), PageRequest(page, size, sort = "code")))

    @GetMapping("/{id}")
    @PreAuthorize("@authz.can('network.odf.view')")
    fun get(@PathVariable id: UUID): OdfView = manageOdf.get(id)

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@authz.can('network.odf.create')")
    fun create(@Valid @RequestBody request: OdfRequest): OdfView = manageOdf.create(request.toCommand())

    @PutMapping("/{id}")
    @PreAuthorize("@authz.can('network.odf.update')")
    fun update(@PathVariable id: UUID, @Valid @RequestBody request: OdfRequest): OdfView =
        manageOdf.update(id, request.toCommand())

    /** Memindah titik ODF di peta (drag); kabel yang menyentuhnya ikut menempel ulang. */
    @PutMapping("/{id}/location")
    @PreAuthorize("@authz.can('network.odf.update')")
    fun relocate(@PathVariable id: UUID, @Valid @RequestBody request: LocationRequest): OdfView =
        manageOdf.relocate(id, request.toCoordinate())

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("@authz.can('network.odf.delete')")
    fun delete(@PathVariable id: UUID) = manageOdf.delete(id)
}

private fun OdfRequest.toCommand() = SaveOdfCommand(
    code = code,
    name = name,
    siteId = siteId,
    location = location.toCoordinate(),
    areaId = areaId,
    portCount = portCount,
    status = status,
)

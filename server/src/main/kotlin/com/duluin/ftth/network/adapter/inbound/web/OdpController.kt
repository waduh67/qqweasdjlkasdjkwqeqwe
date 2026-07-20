package com.duluin.ftth.network.adapter.inbound.web

import com.duluin.ftth.common.domain.PageRequest
import com.duluin.ftth.common.infrastructure.web.PageResponse
import com.duluin.ftth.network.application.port.inbound.ManageOdpUseCase
import com.duluin.ftth.network.application.port.inbound.OdpView
import com.duluin.ftth.network.application.port.inbound.SaveOdpCommand
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
@RequestMapping("/api/odps")
@Tag(name = "Network — ODP")
@SecurityRequirement(name = "bearer-jwt")
class OdpController(
    private val manageOdp: ManageOdpUseCase,
) {
    @GetMapping
    @PreAuthorize("@authz.can('network.odp.view')")
    fun list(
        @RequestParam(required = false) query: String?,
        @RequestParam(required = false) odcId: UUID?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
    ): PageResponse<OdpView> =
        PageResponse.from(manageOdp.search(query.orEmpty(), odcId, PageRequest(page, size, sort = "code")))

    @GetMapping("/{id}")
    @PreAuthorize("@authz.can('network.odp.view')")
    fun get(@PathVariable id: UUID): OdpView = manageOdp.get(id)

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@authz.can('network.odp.create')")
    fun create(@Valid @RequestBody request: OdpRequest): OdpView = manageOdp.create(request.toCommand())

    @PutMapping("/{id}")
    @PreAuthorize("@authz.can('network.odp.update')")
    fun update(@PathVariable id: UUID, @Valid @RequestBody request: OdpRequest): OdpView =
        manageOdp.update(id, request.toCommand())

    /** Menyambung ODP ke ODC; `targetId` null melepas sambungan. */
    @PutMapping("/{id}/uplink")
    @PreAuthorize("@authz.can('network.odp.update')")
    fun connect(@PathVariable id: UUID, @RequestBody request: ConnectRequest): OdpView =
        manageOdp.connect(id, request.targetId)

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("@authz.can('network.odp.delete')")
    fun delete(@PathVariable id: UUID) = manageOdp.delete(id)
}

private fun OdpRequest.toCommand() = SaveOdpCommand(
    code = code,
    name = name,
    address = address,
    location = location.toCoordinate(),
    areaId = areaId,
    odcId = odcId,
    splitterRatio = splitterRatio,
    capacity = capacity,
    status = status,
)

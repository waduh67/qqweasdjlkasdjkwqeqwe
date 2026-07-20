package com.duluin.ftth.network.adapter.inbound.web

import com.duluin.ftth.common.domain.PageRequest
import com.duluin.ftth.common.infrastructure.web.PageResponse
import com.duluin.ftth.network.application.port.inbound.ManageOdcUseCase
import com.duluin.ftth.network.application.port.inbound.OdcView
import com.duluin.ftth.network.application.port.inbound.SaveOdcCommand
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
@RequestMapping("/api/odcs")
@Tag(name = "Network — ODC")
@SecurityRequirement(name = "bearer-jwt")
class OdcController(
    private val manageOdc: ManageOdcUseCase,
) {
    @GetMapping
    @PreAuthorize("@authz.can('network.odc.view')")
    fun list(
        @RequestParam(required = false) query: String?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
    ): PageResponse<OdcView> =
        PageResponse.from(manageOdc.search(query.orEmpty(), PageRequest(page, size, sort = "code")))

    @GetMapping("/{id}")
    @PreAuthorize("@authz.can('network.odc.view')")
    fun get(@PathVariable id: UUID): OdcView = manageOdc.get(id)

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@authz.can('network.odc.create')")
    fun create(@Valid @RequestBody request: OdcRequest): OdcView = manageOdc.create(request.toCommand())

    @PutMapping("/{id}")
    @PreAuthorize("@authz.can('network.odc.update')")
    fun update(@PathVariable id: UUID, @Valid @RequestBody request: OdcRequest): OdcView =
        manageOdc.update(id, request.toCommand())

    /** Menyambung feeder ke PON port; `targetId` null melepas sambungan. */
    @PutMapping("/{id}/uplink")
    @PreAuthorize("@authz.can('network.odc.update')")
    fun connect(@PathVariable id: UUID, @RequestBody request: ConnectRequest): OdcView =
        manageOdc.connect(id, request.targetId)

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("@authz.can('network.odc.delete')")
    fun delete(@PathVariable id: UUID) = manageOdc.delete(id)
}

private fun OdcRequest.toCommand() = SaveOdcCommand(
    code = code,
    name = name,
    address = address,
    location = location.toCoordinate(),
    areaId = areaId,
    ponPortId = ponPortId,
    splitterRatio = splitterRatio,
    capacity = capacity,
    status = status,
)

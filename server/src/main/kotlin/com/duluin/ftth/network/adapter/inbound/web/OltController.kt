package com.duluin.ftth.network.adapter.inbound.web

import com.duluin.ftth.common.domain.PageRequest
import com.duluin.ftth.common.infrastructure.web.PageResponse
import com.duluin.ftth.network.application.port.inbound.ManageOltUseCase
import com.duluin.ftth.network.application.port.inbound.OltView
import com.duluin.ftth.network.application.port.inbound.PonPortView
import com.duluin.ftth.network.application.port.inbound.SaveOltCommand
import com.duluin.ftth.network.application.port.inbound.SavePonPortCommand
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

/**
 * PON port dikelola sebagai sub-resource OLT (`/api/olts/{id}/pon-ports`) karena
 * tidak punya makna di luar OLT induknya — izinnya pun ikut `network.olt.*`.
 */
@RestController
@RequestMapping("/api/olts")
@Tag(name = "Network — OLT")
@SecurityRequirement(name = "bearer-jwt")
class OltController(
    private val manageOlt: ManageOltUseCase,
) {
    @GetMapping
    @PreAuthorize("@authz.can('network.olt.view')")
    fun list(
        @RequestParam(required = false) query: String?,
        @RequestParam(required = false) siteId: UUID?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
    ): PageResponse<OltView> =
        PageResponse.from(manageOlt.search(query.orEmpty(), siteId, PageRequest(page, size, sort = "code")))

    @GetMapping("/{id}")
    @PreAuthorize("@authz.can('network.olt.view')")
    fun get(@PathVariable id: UUID): OltView = manageOlt.get(id)

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@authz.can('network.olt.create')")
    fun create(@Valid @RequestBody request: OltRequest): OltView = manageOlt.create(request.toCommand())

    @PutMapping("/{id}")
    @PreAuthorize("@authz.can('network.olt.update')")
    fun update(@PathVariable id: UUID, @Valid @RequestBody request: OltRequest): OltView =
        manageOlt.update(id, request.toCommand())

    /** Memindah titik OLT di peta (drag); kabel yang menyentuhnya ikut menempel ulang. */
    @PutMapping("/{id}/location")
    @PreAuthorize("@authz.can('network.olt.update')")
    fun relocate(@PathVariable id: UUID, @Valid @RequestBody request: LocationRequest): OltView =
        manageOlt.relocate(id, request.toCoordinate())

    @PutMapping("/{id}/status")
    @PreAuthorize("@authz.can('network.olt.update')")
    fun changeStatus(@PathVariable id: UUID, @RequestBody request: StatusRequest): OltView =
        manageOlt.changeStatus(id, request.status)

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("@authz.can('network.olt.delete')")
    fun delete(@PathVariable id: UUID) = manageOlt.delete(id)

    @GetMapping("/{id}/pon-ports")
    @PreAuthorize("@authz.can('network.olt.view')")
    fun listPonPorts(@PathVariable id: UUID): List<PonPortView> = manageOlt.listPonPorts(id)

    @PostMapping("/{id}/pon-ports")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@authz.can('network.olt.update')")
    fun addPonPort(@PathVariable id: UUID, @Valid @RequestBody request: PonPortRequest): PonPortView =
        manageOlt.addPonPort(id, request.toCommand())

    @PutMapping("/pon-ports/{portId}")
    @PreAuthorize("@authz.can('network.olt.update')")
    fun updatePonPort(@PathVariable portId: UUID, @Valid @RequestBody request: PonPortRequest): PonPortView =
        manageOlt.updatePonPort(portId, request.toCommand())

    @DeleteMapping("/pon-ports/{portId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("@authz.can('network.olt.update')")
    fun deletePonPort(@PathVariable portId: UUID) = manageOlt.deletePonPort(portId)
}

private fun OltRequest.toCommand() = SaveOltCommand(
    siteId = siteId,
    code = code,
    name = name,
    vendor = vendor,
    model = model,
    managementIp = managementIp,
    snmpCommunity = snmpCommunity,
    snmpPort = snmpPort,
    location = location?.toCoordinate(),
    description = description,
    snmpEnabled = snmpEnabled,
    snmpVersion = snmpVersion,
    webEnabled = webEnabled,
    webProtocol = webProtocol,
    webPort = webPort,
    webUsername = webUsername,
    webPassword = webPassword,
)

private fun PonPortRequest.toCommand() = SavePonPortCommand(
    label = label,
    description = description,
    status = status,
)

package com.duluin.ftth.network.adapter.inbound.web

import com.duluin.ftth.common.domain.PageRequest
import com.duluin.ftth.common.infrastructure.web.PageResponse
import com.duluin.ftth.network.application.port.inbound.JointBoxView
import com.duluin.ftth.network.application.port.inbound.ManageJointBoxUseCase
import com.duluin.ftth.network.application.port.inbound.SaveJointBoxCommand
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
@RequestMapping("/api/joint-boxes")
@Tag(name = "Network — Joint box")
@SecurityRequirement(name = "bearer-jwt")
class JointBoxController(
    private val manageJointBox: ManageJointBoxUseCase,
) {
    @GetMapping
    @PreAuthorize("@authz.can('network.jointbox.view')")
    fun list(
        @RequestParam(required = false) query: String?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
    ): PageResponse<JointBoxView> =
        PageResponse.from(manageJointBox.search(query.orEmpty(), PageRequest(page, size, sort = "code")))

    @GetMapping("/{id}")
    @PreAuthorize("@authz.can('network.jointbox.view')")
    fun get(@PathVariable id: UUID): JointBoxView = manageJointBox.get(id)

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@authz.can('network.jointbox.create')")
    fun create(@Valid @RequestBody request: JointBoxRequest): JointBoxView =
        manageJointBox.create(request.toCommand())

    @PutMapping("/{id}")
    @PreAuthorize("@authz.can('network.jointbox.update')")
    fun update(@PathVariable id: UUID, @Valid @RequestBody request: JointBoxRequest): JointBoxView =
        manageJointBox.update(id, request.toCommand())

    /** Memindah titik JB di peta (drag); kabel yang menyentuhnya ikut menempel ulang. */
    @PutMapping("/{id}/location")
    @PreAuthorize("@authz.can('network.jointbox.update')")
    fun relocate(@PathVariable id: UUID, @Valid @RequestBody request: LocationRequest): JointBoxView =
        manageJointBox.relocate(id, request.toCoordinate())

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("@authz.can('network.jointbox.delete')")
    fun delete(@PathVariable id: UUID) = manageJointBox.delete(id)
}

private fun JointBoxRequest.toCommand() = SaveJointBoxCommand(
    code = code,
    name = name,
    address = address,
    location = location.toCoordinate(),
    areaId = areaId,
    trayCount = trayCount,
    capacity = capacity,
    status = status,
)

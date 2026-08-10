package com.duluin.ftth.network.adapter.inbound.web

import com.duluin.ftth.common.domain.PageRequest
import com.duluin.ftth.common.infrastructure.web.PageResponse
import com.duluin.ftth.network.application.port.inbound.CableCoreListView
import com.duluin.ftth.network.application.port.inbound.CablePortOption
import com.duluin.ftth.network.application.port.inbound.CableView
import com.duluin.ftth.network.application.port.inbound.ManageCableCoreUseCase
import com.duluin.ftth.network.application.port.inbound.ManageCableUseCase
import com.duluin.ftth.network.application.port.inbound.SaveCableCommand
import com.duluin.ftth.network.application.port.inbound.UpdateCableCoresCommand
import com.duluin.ftth.network.domain.model.CableType
import com.duluin.ftth.network.domain.model.NetworkNodeKind
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
@RequestMapping("/api/cables")
@Tag(name = "Network — Kabel")
@SecurityRequirement(name = "bearer-jwt")
class CableController(
    private val manageCable: ManageCableUseCase,
    private val manageCableCore: ManageCableCoreUseCase,
) {
    @GetMapping
    @PreAuthorize("@authz.can('network.cable.view')")
    fun list(
        @RequestParam(required = false) query: String?,
        @RequestParam(required = false) cableType: CableType?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
    ): PageResponse<CableView> =
        PageResponse.from(manageCable.search(query.orEmpty(), cableType, PageRequest(page, size, sort = "code")))

    @GetMapping("/{id}")
    @PreAuthorize("@authz.can('network.cable.view')")
    fun get(@PathVariable id: UUID): CableView = manageCable.get(id)

    /** Port keluaran yang tersedia di simpul sumber — untuk picker "colok dari port mana". */
    @GetMapping("/source-ports")
    @PreAuthorize("@authz.can('network.cable.view')")
    fun sourcePorts(
        @RequestParam kind: NetworkNodeKind,
        @RequestParam id: UUID,
    ): List<CablePortOption> = manageCable.sourcePorts(kind, id)

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@authz.can('network.cable.create')")
    fun create(@Valid @RequestBody request: CableRequest): CableView = manageCable.create(request.toCommand())

    @PutMapping("/{id}")
    @PreAuthorize("@authz.can('network.cable.update')")
    fun update(@PathVariable id: UUID, @Valid @RequestBody request: CableRequest): CableView =
        manageCable.update(id, request.toCommand())

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("@authz.can('network.cable.delete')")
    fun delete(@PathVariable id: UUID) = manageCable.delete(id)

    /** Barisan core kabel + hitungan per status — bahan layar "Kelola Core". */
    @GetMapping("/{id}/cores")
    @PreAuthorize("@authz.can('network.cable.view')")
    fun cores(@PathVariable id: UUID): CableCoreListView = manageCableCore.list(id)

    /** Setel status/catatan satu atau banyak core sekaligus. */
    @PutMapping("/{id}/cores")
    @PreAuthorize("@authz.can('network.cable.update')")
    fun updateCores(@PathVariable id: UUID, @Valid @RequestBody request: CableCoresRequest): CableCoreListView =
        manageCableCore.update(
            id,
            UpdateCableCoresCommand(
                coreNumbers = request.coreNumbers,
                status = request.status,
                note = request.note,
                clearNote = request.clearNote,
            ),
        )
}

private fun CableRequest.toCommand() = SaveCableCommand(
    code = code,
    name = name,
    cableType = cableType,
    coreCount = coreCount,
    route = route.map { it.toCoordinate() },
    fromKind = fromKind,
    fromId = fromId,
    toKind = toKind,
    toId = toId,
    fromPonPortId = fromPonPortId,
    fromPortNumber = fromPortNumber,
    toPortNumber = toPortNumber,
    status = status,
)

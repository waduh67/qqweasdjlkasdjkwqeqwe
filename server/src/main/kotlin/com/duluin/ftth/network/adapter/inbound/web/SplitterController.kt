package com.duluin.ftth.network.adapter.inbound.web

import com.duluin.ftth.network.application.port.inbound.ClosureSplitterView
import com.duluin.ftth.network.application.port.inbound.ManageSplitterUseCase
import com.duluin.ftth.network.application.port.inbound.SaveSplitterCommand
import com.duluin.ftth.network.application.port.inbound.SplitterView
import com.duluin.ftth.network.application.port.inbound.UpdateSplitterCommand
import com.duluin.ftth.network.domain.model.ClosureKind
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
 * Modul splitter selalu dibaca PER KABINET, tak pernah sebagai daftar sedunia:
 * pertanyaannya memang "isi ODC-A apa saja", bukan "berapa splitter yang saya
 * punya". Karena itu tak ada endpoint daftar berhalaman di sini.
 */
@RestController
@RequestMapping("/api/splitters")
@Tag(name = "Network — Splitter")
@SecurityRequirement(name = "bearer-jwt")
class SplitterController(
    private val manageSplitter: ManageSplitterUseCase,
) {
    @GetMapping
    @PreAuthorize("@authz.can('network.splitter.view')")
    fun list(
        @RequestParam ownerKind: ClosureKind,
        @RequestParam ownerId: UUID,
    ): ClosureSplitterView = manageSplitter.list(ownerKind, ownerId)

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@authz.can('network.splitter.create')")
    fun create(@Valid @RequestBody request: SplitterRequest): SplitterView =
        manageSplitter.create(
            SaveSplitterCommand(
                ownerKind = request.ownerKind,
                ownerId = request.ownerId,
                code = request.code,
                ratio = request.ratio,
                note = request.note,
            ),
        )

    @PutMapping("/{id}")
    @PreAuthorize("@authz.can('network.splitter.update')")
    fun update(@PathVariable id: UUID, @Valid @RequestBody request: UpdateSplitterRequest): SplitterView =
        manageSplitter.update(id, UpdateSplitterCommand(ratio = request.ratio, note = request.note))

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("@authz.can('network.splitter.delete')")
    fun delete(@PathVariable id: UUID) = manageSplitter.delete(id)
}

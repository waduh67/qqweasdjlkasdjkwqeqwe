package com.duluin.ftth.iam.adapter.inbound.web

import com.duluin.ftth.iam.application.port.inbound.AreaView
import com.duluin.ftth.iam.application.port.inbound.CreateAreaCommand
import com.duluin.ftth.iam.application.port.inbound.ManageAreaUseCase
import com.duluin.ftth.iam.application.port.inbound.UpdateAreaCommand
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import org.springframework.http.HttpStatus
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/areas")
@Tag(name = "Areas")
@SecurityRequirement(name = "bearer-jwt")
class AreaController(
    private val manageArea: ManageAreaUseCase,
) {
    @GetMapping
    @PreAuthorize("@authz.can('iam.area.view')")
    fun list(): List<AreaView> = manageArea.list()

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@authz.can('iam.area.create')")
    fun create(@Valid @RequestBody request: CreateAreaRequest): AreaView =
        manageArea.create(CreateAreaCommand(request.code, request.name, request.parentId))

    @PutMapping("/{id}")
    @PreAuthorize("@authz.can('iam.area.update')")
    fun update(@PathVariable id: UUID, @Valid @RequestBody request: UpdateAreaRequest): AreaView =
        manageArea.update(id, UpdateAreaCommand(request.name, request.parentId))

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("@authz.can('iam.area.delete')")
    fun delete(@PathVariable id: UUID) = manageArea.delete(id)
}

data class CreateAreaRequest(
    @field:NotBlank val code: String,
    @field:NotBlank val name: String,
    val parentId: UUID? = null,
)

data class UpdateAreaRequest(
    @field:NotBlank val name: String,
    val parentId: UUID? = null,
)

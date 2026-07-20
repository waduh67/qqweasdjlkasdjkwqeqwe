package com.duluin.ftth.iam.adapter.inbound.web

import com.duluin.ftth.iam.application.port.inbound.CreateRoleCommand
import com.duluin.ftth.iam.application.port.inbound.ManageRoleUseCase
import com.duluin.ftth.iam.application.port.inbound.RoleView
import com.duluin.ftth.iam.application.port.inbound.UpdateRoleCommand
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
@RequestMapping("/api/roles")
@Tag(name = "Roles")
@SecurityRequirement(name = "bearer-jwt")
class RoleController(
    private val manageRole: ManageRoleUseCase,
) {
    @GetMapping
    @PreAuthorize("@authz.can('iam.role.view')")
    fun list(): List<RoleView> = manageRole.list()

    @GetMapping("/{id}")
    @PreAuthorize("@authz.can('iam.role.view')")
    fun get(@PathVariable id: UUID): RoleView = manageRole.get(id)

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@authz.can('iam.role.create')")
    fun create(@Valid @RequestBody request: RoleRequest): RoleView =
        manageRole.create(CreateRoleCommand(request.name, request.description, request.permissionIds))

    @PutMapping("/{id}")
    @PreAuthorize("@authz.can('iam.role.update')")
    fun update(@PathVariable id: UUID, @Valid @RequestBody request: RoleRequest): RoleView =
        manageRole.update(id, UpdateRoleCommand(request.name, request.description, request.permissionIds))

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("@authz.can('iam.role.delete')")
    fun delete(@PathVariable id: UUID) = manageRole.delete(id)
}

data class RoleRequest(
    @field:NotBlank val name: String,
    val description: String? = null,
    val permissionIds: Set<UUID> = emptySet(),
)

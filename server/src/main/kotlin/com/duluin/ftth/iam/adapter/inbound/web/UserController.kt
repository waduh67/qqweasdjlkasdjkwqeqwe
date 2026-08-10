package com.duluin.ftth.iam.adapter.inbound.web

import com.duluin.ftth.common.domain.PageRequest
import com.duluin.ftth.common.infrastructure.web.PageResponse
import com.duluin.ftth.iam.application.port.inbound.AssignAccessCommand
import com.duluin.ftth.iam.application.port.inbound.CreateUserCommand
import com.duluin.ftth.iam.application.port.inbound.ManageTwoFactorUseCase
import com.duluin.ftth.iam.application.port.inbound.ManageUserUseCase
import com.duluin.ftth.iam.application.port.inbound.UpdateUserCommand
import com.duluin.ftth.iam.application.port.inbound.UserView
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
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/users")
@Tag(name = "Users")
@SecurityRequirement(name = "bearer-jwt")
class UserController(
    private val manageUser: ManageUserUseCase,
    private val twoFactor: ManageTwoFactorUseCase,
) {
    @GetMapping
    @PreAuthorize("@authz.can('iam.user.view')")
    fun list(
        @RequestParam(required = false) query: String?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
    ): PageResponse<UserView> =
        PageResponse.from(manageUser.list(query, PageRequest(page, size, sort = "createdAt", descending = true)))

    @GetMapping("/{id}")
    @PreAuthorize("@authz.can('iam.user.view')")
    fun get(@PathVariable id: UUID): UserView = manageUser.get(id)

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@authz.can('iam.user.create')")
    fun create(@Valid @RequestBody request: CreateUserRequest): UserView =
        manageUser.create(
            CreateUserCommand(request.email, request.name, request.password, request.roleIds, request.areaIds),
        )

    @PutMapping("/{id}")
    @PreAuthorize("@authz.can('iam.user.update')")
    fun update(@PathVariable id: UUID, @Valid @RequestBody request: UpdateUserRequest): UserView =
        manageUser.update(id, UpdateUserCommand(request.name))

    @PutMapping("/{id}/access")
    @PreAuthorize("@authz.can('iam.user.assign')")
    fun assignAccess(@PathVariable id: UUID, @Valid @RequestBody request: AssignAccessRequest): UserView =
        manageUser.assignAccess(id, AssignAccessCommand(request.roleIds, request.areaIds))

    @PostMapping("/{id}/enable")
    @PreAuthorize("@authz.can('iam.user.update')")
    fun enable(@PathVariable id: UUID): UserView = manageUser.setEnabled(id, enabled = true)

    @PostMapping("/{id}/disable")
    @PreAuthorize("@authz.can('iam.user.update')")
    fun disable(@PathVariable id: UUID): UserView = manageUser.setEnabled(id, enabled = false)

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("@authz.can('iam.user.delete')")
    fun delete(@PathVariable id: UUID) = manageUser.delete(id)

    /**
     * Kosongkan 2FA orang lain — untuk ponsel hilang tanpa kode pemulihan tersisa. Bukan
     * memindahkan faktor kedua ke perangkat siapa pun: pemiliknya masuk lagi dengan
     * password saja, lalu mendaftarkan perangkat barunya sendiri.
     */
    @PostMapping("/{id}/2fa/reset")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("@authz.can('iam.user.update')")
    fun resetTwoFactor(@PathVariable id: UUID) = twoFactor.resetFor(id)
}

data class CreateUserRequest(
    @field:NotBlank val email: String,
    @field:NotBlank val name: String,
    @field:NotBlank val password: String,
    val roleIds: Set<UUID> = emptySet(),
    val areaIds: Set<UUID> = emptySet(),
)

data class UpdateUserRequest(
    @field:NotBlank val name: String,
)

data class AssignAccessRequest(
    val roleIds: Set<UUID> = emptySet(),
    val areaIds: Set<UUID> = emptySet(),
)

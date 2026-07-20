package com.duluin.ftth.iam.application.port.inbound

import com.duluin.ftth.common.domain.Page
import com.duluin.ftth.common.domain.PageRequest
import java.util.UUID

interface ManageUserUseCase {

    fun create(command: CreateUserCommand): UserView

    fun update(id: UUID, command: UpdateUserCommand): UserView

    fun assignAccess(id: UUID, command: AssignAccessCommand): UserView

    fun setEnabled(id: UUID, enabled: Boolean): UserView

    fun delete(id: UUID)

    fun get(id: UUID): UserView

    fun list(query: String?, pageRequest: PageRequest): Page<UserView>
}

data class CreateUserCommand(
    val email: String,
    val name: String,
    val password: String,
    val roleIds: Set<UUID> = emptySet(),
    val areaIds: Set<UUID> = emptySet(),
)

data class UpdateUserCommand(
    val name: String,
)

/** Atur role & area (dimensi scope) sekaligus. */
data class AssignAccessCommand(
    val roleIds: Set<UUID>,
    val areaIds: Set<UUID>,
)

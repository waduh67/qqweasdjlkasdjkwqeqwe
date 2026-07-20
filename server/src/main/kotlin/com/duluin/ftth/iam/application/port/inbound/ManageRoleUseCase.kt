package com.duluin.ftth.iam.application.port.inbound

import java.util.UUID

interface ManageRoleUseCase {

    fun create(command: CreateRoleCommand): RoleView

    fun update(id: UUID, command: UpdateRoleCommand): RoleView

    fun delete(id: UUID)

    fun get(id: UUID): RoleView

    fun list(): List<RoleView>
}

data class CreateRoleCommand(
    val name: String,
    val description: String?,
    val permissionIds: Set<UUID>,
)

data class UpdateRoleCommand(
    val name: String,
    val description: String?,
    val permissionIds: Set<UUID>,
)

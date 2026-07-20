package com.duluin.ftth.iam.application.port.inbound

import java.util.UUID

interface ManageAreaUseCase {

    fun create(command: CreateAreaCommand): AreaView

    fun update(id: UUID, command: UpdateAreaCommand): AreaView

    fun delete(id: UUID)

    fun list(): List<AreaView>
}

data class CreateAreaCommand(
    val code: String,
    val name: String,
    val parentId: UUID?,
)

data class UpdateAreaCommand(
    val name: String,
    val parentId: UUID?,
)

package com.duluin.ftth.iam.application.port.outbound

import java.util.UUID

interface TenantOwnerStore {
    fun findUserId(): UUID?
    fun bindIfMissing(userId: UUID): Boolean
    fun bind(userId: UUID, actorId: UUID)
    fun backfillInitialAdmin(): Boolean
}

package com.duluin.ftth.iam.application.port.outbound

import java.util.UUID
import com.duluin.ftth.iam.TenantOwnerRef

interface TenantOwnerStore {
    fun findProfiles(tenantIds: Set<UUID>): Map<UUID, TenantOwnerRef?>
    fun findUserId(): UUID?
    fun bindIfMissing(userId: UUID): Boolean
    fun bind(userId: UUID, actorId: UUID)
    fun backfillInitialAdmin(): Boolean
}

package com.karuhun.ftth.iam.application.port.outbound

import java.util.UUID
import com.karuhun.ftth.iam.TenantOwnerRef
import com.karuhun.ftth.iam.CurrentTenantOwnerApi

interface TenantOwnerStore : CurrentTenantOwnerApi {
    fun findProfiles(tenantIds: Set<UUID>): Map<UUID, TenantOwnerRef?>
    override fun findUserId(): UUID?
    fun bindIfMissing(userId: UUID): Boolean
    fun bind(userId: UUID, actorId: UUID)
    fun backfillInitialAdmin(): Boolean
}

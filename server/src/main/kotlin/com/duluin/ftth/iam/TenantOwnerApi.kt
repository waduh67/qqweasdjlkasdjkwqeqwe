package com.duluin.ftth.iam

import com.duluin.ftth.common.domain.Page
import com.duluin.ftth.common.domain.PageRequest
import java.util.UUID

interface TenantOwnerApi {
    fun findOwners(tenantIds: Set<UUID>): Map<UUID, TenantOwnerRef?>
    fun candidates(tenantId: UUID, query: String?, page: PageRequest): Page<TenantOwnerRef>
    fun bind(tenantId: UUID, userId: UUID): TenantOwnerRef
    fun resetPassword(tenantId: UUID, command: OwnerPasswordReset)
}

data class TenantOwnerRef(val id: UUID, val name: String, val email: String, val status: String)
data class OwnerPasswordReset(val expectedOwnerUserId: UUID, val newPassword: String)

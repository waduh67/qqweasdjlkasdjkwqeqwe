package com.duluin.ftth.iam

import com.duluin.ftth.common.security.AuthorityFence
import java.util.UUID

interface ApprovalAuthorityApi {
    fun directory(fence: AuthorityFence): ApprovalAuthorityDirectory
}

data class ApprovalAuthorityDirectory(val users: List<ApprovalPrincipal>, val roles: Map<UUID, Set<String>>)
data class ApprovalPrincipal(val id: UUID, val roleIds: Set<UUID>, val permissions: Set<String>, val areaIds: Set<UUID>)

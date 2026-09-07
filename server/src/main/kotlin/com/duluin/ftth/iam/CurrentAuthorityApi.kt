package com.duluin.ftth.iam

import com.duluin.ftth.common.security.AuthorityChangeFence
import com.duluin.ftth.common.security.AuthorityFence
import com.duluin.ftth.common.security.AuthorityScope
import java.util.UUID

interface CurrentAuthorityApi {
    fun lockCurrent(): CurrentAuthority
    fun lockForChange(): AuthorityChangeFence
}

data class CurrentAuthority(
    val fence: AuthorityFence,
    val roleIds: Set<UUID>,
    val permissions: Set<String>,
    val areaScope: AuthorityScope,
    val platformAdmin: Boolean,
)

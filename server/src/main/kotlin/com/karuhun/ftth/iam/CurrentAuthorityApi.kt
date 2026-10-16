package com.karuhun.ftth.iam

import com.karuhun.ftth.common.security.AuthorityChangeFence
import com.karuhun.ftth.common.security.AuthorityFence
import com.karuhun.ftth.common.security.AuthorityScope
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

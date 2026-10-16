package com.karuhun.ftth.iam

import com.karuhun.ftth.common.domain.error.AccessDeniedException
import com.karuhun.ftth.common.security.AuthorityChangeFence

fun CurrentAuthorityApi.authorizeChange(permission: String): AuthorityChangeFence {
    val change = lockForChange()
    val current = lockCurrent()
    if (!current.platformAdmin && permission !in current.permissions) throw AccessDeniedException("Current mutation permission required")
    return change
}

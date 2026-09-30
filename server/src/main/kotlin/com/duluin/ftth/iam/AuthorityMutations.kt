package com.duluin.ftth.iam

import com.duluin.ftth.common.domain.error.AccessDeniedException
import com.duluin.ftth.common.security.AuthorityChangeFence

fun CurrentAuthorityApi.authorizeChange(permission: String): AuthorityChangeFence {
    val change = lockForChange()
    val current = lockCurrent()
    if (!current.platformAdmin && permission !in current.permissions) throw AccessDeniedException("Current mutation permission required")
    return change
}

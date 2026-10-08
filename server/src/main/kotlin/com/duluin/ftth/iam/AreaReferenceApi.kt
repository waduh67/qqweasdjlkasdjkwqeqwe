package com.duluin.ftth.iam

import com.duluin.ftth.common.security.AuthorityScope

interface AreaReferenceApi {
    fun areasInScope(scope: AuthorityScope): List<AreaRef>
}

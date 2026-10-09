package com.duluin.ftth.iam

import com.duluin.ftth.common.security.AuthorityScope
import com.duluin.ftth.common.domain.Page
import com.duluin.ftth.common.domain.PageRequest

interface AreaReferenceApi {
    fun areasInScope(scope: AuthorityScope): List<AreaRef>
    fun searchAreas(scope: AuthorityScope, query: String, page: PageRequest): Page<AreaRef>
}

package com.karuhun.ftth.iam

import com.karuhun.ftth.common.security.AuthorityScope
import com.karuhun.ftth.common.domain.Page
import com.karuhun.ftth.common.domain.PageRequest

interface AreaReferenceApi {
    fun areasInScope(scope: AuthorityScope): List<AreaRef>
    fun searchAreas(scope: AuthorityScope, query: String, page: PageRequest): Page<AreaRef>
}

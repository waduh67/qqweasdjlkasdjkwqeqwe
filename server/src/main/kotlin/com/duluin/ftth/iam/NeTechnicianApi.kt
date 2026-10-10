package com.duluin.ftth.iam

import com.duluin.ftth.common.domain.Page
import com.duluin.ftth.common.domain.PageRequest

interface NeTechnicianApi {
    fun searchNetworkEngineers(query: String, page: PageRequest): Page<UserRef>
}

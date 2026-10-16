package com.karuhun.ftth.iam

import com.karuhun.ftth.common.domain.Page
import com.karuhun.ftth.common.domain.PageRequest

interface NeTechnicianApi {
    fun searchNetworkEngineers(query: String, page: PageRequest): Page<UserRef>
}

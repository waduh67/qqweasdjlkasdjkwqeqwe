package com.karuhun.ftth.inventory

import com.karuhun.ftth.common.security.AuthorityFence
import com.karuhun.ftth.common.security.AuthorityScope

interface InventoryWarehouseScopeApi {
    fun currentUnderFence(authority: AuthorityFence): AuthorityScope
}

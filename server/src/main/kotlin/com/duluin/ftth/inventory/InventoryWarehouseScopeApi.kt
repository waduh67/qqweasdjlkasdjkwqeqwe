package com.duluin.ftth.inventory

import com.duluin.ftth.common.security.AuthorityFence
import com.duluin.ftth.common.security.AuthorityScope

interface InventoryWarehouseScopeApi {
    fun currentUnderFence(authority: AuthorityFence): AuthorityScope
}

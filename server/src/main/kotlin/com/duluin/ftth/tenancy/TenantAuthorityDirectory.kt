package com.duluin.ftth.tenancy

import java.util.UUID

interface TenantAuthorityDirectory {
    fun allTenantIds(): List<UUID>
}

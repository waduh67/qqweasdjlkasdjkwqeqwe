package com.karuhun.ftth.tenancy

import java.util.UUID

interface TenantAuthorityDirectory {
    fun allTenantIds(): List<UUID>
}

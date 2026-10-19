package com.karuhun.ftth.iam

import java.util.UUID

/** Read-only owner identity in the caller's current tenant transaction. */
interface CurrentTenantOwnerApi {
    fun findUserId(): UUID?
}

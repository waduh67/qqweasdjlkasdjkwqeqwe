package com.karuhun.ftth.tenancy

import com.karuhun.ftth.common.domain.Page
import com.karuhun.ftth.common.domain.PageRequest
import java.util.UUID

/** Public management port used by platform tenant/owner orchestration in iam. */
interface TenantManagementApi {

    fun list(pageRequest: PageRequest): Page<TenantRef>

    fun get(id: UUID): TenantRef

    fun suspend(id: UUID): TenantRef

    fun activate(id: UUID): TenantRef

    /** Hapus PERMANEN tenant beserta seluruh datanya. Tenant `platform` tak bisa dihapus. */
    fun delete(id: UUID)
}

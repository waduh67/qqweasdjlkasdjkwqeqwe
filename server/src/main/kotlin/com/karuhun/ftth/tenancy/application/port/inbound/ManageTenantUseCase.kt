package com.karuhun.ftth.tenancy.application.port.inbound

import com.karuhun.ftth.common.domain.Page
import com.karuhun.ftth.common.domain.PageRequest
import com.karuhun.ftth.tenancy.TenantRef
import java.util.UUID

/** Use case pengelolaan tenant untuk platform admin (dipakai lapisan web). */
interface ManageTenantUseCase {

    fun list(pageRequest: PageRequest): Page<TenantRef>

    fun get(id: UUID): TenantRef

    fun suspend(id: UUID): TenantRef

    fun activate(id: UUID): TenantRef

    /** Hapus PERMANEN tenant beserta seluruh datanya. Tenant `platform` tak bisa dihapus. */
    fun delete(id: UUID)
}

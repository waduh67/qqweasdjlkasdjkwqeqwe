package com.duluin.ftth.common.tenant

import java.util.UUID

/**
 * Tenant aktif untuk thread/request saat ini.
 *
 * Diisi oleh filter web (dari klaim JWT) atau secara eksplisit lewat [runAs]
 * (login, seeder, listener lintas-tenant). Hibernate membacanya lewat
 * `CurrentTenantIdentifierResolver`, dan connection provider meneruskannya ke
 * Postgres sebagai GUC `app.tenant_id` untuk Row-Level Security.
 *
 * ATURAN: jangan mengganti tenant di tengah transaksi/EntityManager yang sudah
 * terbuka — tenant di-resolve saat session Hibernate pertama kali dibuka.
 */
object TenantContext {
    private val current = ThreadLocal<UUID?>()

    fun tenantIdOrNull(): UUID? = current.get()

    fun tenantId(): UUID =
        current.get() ?: error("Tidak ada tenant di context — panggil dalam request terautentikasi atau TenantContext.runAs")

    fun set(tenantId: UUID?) {
        if (tenantId == null) current.remove() else current.set(tenantId)
    }

    fun clear() = current.remove()

    /** Jalankan [block] seolah-olah dalam tenant [tenantId], lalu pulihkan state sebelumnya. */
    fun <T> runAs(tenantId: UUID?, block: () -> T): T {
        val prev = current.get()
        set(tenantId)
        try {
            return block()
        } finally {
            set(prev)
        }
    }
}

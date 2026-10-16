package com.karuhun.ftth.billing.application.port.outbound

import com.karuhun.ftth.billing.domain.model.TenantPayout

/**
 * Riwayat penyaluran dana per-tenant (tenant-scoped + RLS). [list] mengembalikan histori tenant
 * aktif terbaru-dahulu; [findByReference] dipakai rekonsiliasi callback (cari baris via ref Pivot).
 */
interface TenantPayoutRepository {
    fun save(payout: TenantPayout): TenantPayout
    fun list(): List<TenantPayout>
    fun findByReference(reference: String): TenantPayout?
}

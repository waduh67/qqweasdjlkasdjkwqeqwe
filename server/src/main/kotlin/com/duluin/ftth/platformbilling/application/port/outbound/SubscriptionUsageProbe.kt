package com.duluin.ftth.platformbilling.application.port.outbound

/**
 * Menghitung pemakaian (kosmetik) tenant pada konteks berjalan untuk halaman langganan.
 * Bukan batas nyata — sekadar tampilan "N/Unlimited". Menghitung via koneksi Hibernate
 * agar Row-Level Security ikut menyaring ke tenant aktif.
 */
interface SubscriptionUsageProbe {
    fun currentTenantUsage(): List<UsageCount>
}

/** Satu metrik pemakaian mentah (label + jumlah baris tenant). */
data class UsageCount(val key: String, val label: String, val used: Long)

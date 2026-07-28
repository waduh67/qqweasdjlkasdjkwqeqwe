package com.duluin.ftth.catalog

import java.math.BigDecimal
import java.util.UUID

/**
 * Kontrak publik modul `catalog` untuk modul lain. Dibagi dua pandangan sesuai apa
 * yang dibutuhkan konsumen:
 *  - [PlanCommercialRef] dipakai `customer` untuk MEN-SNAPSHOT sisi komersial
 *    (harga + override siklus billing) ke langganan saat create/aktivasi.
 *  - [PlanNetworkRef] dipakai `bng` untuk membaca LIVE sisi jaringan (kecepatan +
 *    atribut RADIUS Mikrotik-Rate-Limit yang sudah dirakit) saat provision & re-sync.
 *
 * Catalog adalah "sink": tak pernah memanggil balik customer/bng. Enum internal tak
 * pernah bocor lintas-modul (mis. tipe layanan dibalikan sebagai `Set<String>`).
 */
interface CatalogApi {

    /** Sisi komersial paket untuk di-snapshot langganan. `null` bila paket tak ada. */
    fun findPlanCommercial(planId: UUID): PlanCommercialRef?

    /** Sisi jaringan paket untuk penegakan RADIUS. `null` bila paket tak ada. */
    fun findPlanNetwork(planId: UUID): PlanNetworkRef?
}

/**
 * Sisi komersial paket. `packageName`/`monthlyFee` sengaja dinamai selaras dengan snapshot
 * langganan agar penyalinan lugas. Override siklus null = ikut kebijakan billing global.
 */
data class PlanCommercialRef(
    val planId: UUID,
    val packageName: String,
    val monthlyFee: BigDecimal,
    val bandwidthMbps: Int,
    val active: Boolean,
    val prorateOnActivation: Boolean?,
    val billingDayOfMonth: Int?,
    val dueDays: Int?,
    val graceDays: Int?,
    val autoIsolir: Boolean?,
)

/**
 * Sisi jaringan paket. [rateLimit] sudah dirakit jadi atribut Mikrotik-Rate-Limit siap
 * tulis ke `radgroupreply`; [downMbps]/[upMbps] disertakan untuk CoA numerik. FUP: bila
 * [fupEnabled], [fupRateLimit] adalah kecepatan throttle grup kedua.
 */
data class PlanNetworkRef(
    val planId: UUID,
    val name: String,
    val downMbps: Int,
    val upMbps: Int,
    val rateLimit: String,
    val connectionLimit: Int?,
    val fupEnabled: Boolean,
    val fupQuotaMb: Long?,
    val fupRateLimit: String?,
)

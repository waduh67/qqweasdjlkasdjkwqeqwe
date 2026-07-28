package com.duluin.ftth.catalog.application.port.inbound

import java.math.BigDecimal
import java.util.UUID

/**
 * Proyeksi satu paket untuk UI. Menyertakan [rateLimit]/[fupRateLimit] yang sudah
 * dirakit server sehingga preview di form persis dengan yang ditulis ke RADIUS —
 * operator tak pernah lagi mengetik string profil sendiri. [serviceTypes] dibalikkan
 * sebagai nama enum agar bebas kaitan tipe di web.
 */
data class PlanView(
    val id: UUID,
    val name: String,
    val description: String?,
    val price: BigDecimal,
    val downMbps: Int,
    val upMbps: Int,
    val downBurstMbps: Int?,
    val upBurstMbps: Int?,
    val downThresholdMbps: Int?,
    val upThresholdMbps: Int?,
    val burstTimeSec: Int?,
    val downMinMbps: Int?,
    val upMinMbps: Int?,
    val priority: Int,
    val connectionLimit: Int?,
    val fupEnabled: Boolean,
    val fupQuotaMb: Long?,
    val fupDownMbps: Int?,
    val fupUpMbps: Int?,
    val serviceTypes: Set<String>,
    val prorateOnActivation: Boolean?,
    val billingDayOfMonth: Int?,
    val dueDays: Int?,
    val graceDays: Int?,
    val autoIsolir: Boolean?,
    val active: Boolean,
    /** Atribut Mikrotik-Rate-Limit siap-tulis yang dirakit dari field terstruktur. */
    val rateLimit: String,
    /** Atribut throttle grup FUP; null bila paket tak ber-FUP. */
    val fupRateLimit: String?,
)

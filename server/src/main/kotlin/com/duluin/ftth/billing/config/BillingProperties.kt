package com.duluin.ftth.billing.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Kebijakan penagihan tenant (nilai default dev; ditimpa lewat environment di prod).
 *
 * [webhookSecret] adalah rahasia verifikasi callback MANUAL — WAJIB diisi via env di produksi
 * dan tidak pernah ikut ter-commit dengan nilai asli. Kredensial Pivot TIDAK di sini melainkan
 * di `pivot_master_config` (setelan platform-admin), agar bisa dirotasi tanpa redeploy.
 */
@ConfigurationProperties(prefix = "ftth.billing")
data class BillingProperties(
    /** Tanggal minimal dalam bulan saat scheduler boleh menerbitkan tagihan. */
    val billingDayOfMonth: Int = 1,
    /** Jarak hari dari terbit ke jatuh tempo. */
    val dueDays: Long = 7,
    /** Masa tenggang setelah jatuh tempo sebelum ditandai menunggak. */
    val graceDays: Long = 3,
    /** Berapa hari sebelum jatuh tempo pelanggan diingatkan (pemicu notifikasi INVOICE_DUE_SOON). */
    val reminderDaysBefore: Long = 3,
    /** Otomatis isolir langganan saat tagihannya menunggak. */
    val autoIsolir: Boolean = true,
    /** Prorata tagihan pertama saat langganan aktif di tengah bulan (default global). */
    val prorateOnActivation: Boolean = false,
    val numberPrefix: String = "INV",
    val defaultProvider: String = "MANUAL",
    /** Selang jalannya scheduler penagihan (ISO-8601 duration). */
    val schedulerInterval: String = "PT12H",
    val webhookSecret: String = "dev-only-billing-webhook-secret-change-me",
    /** Setelan adapter Pivot: URL balik wajib mode REDIRECT (success/failure/expiration diturunkan darinya). */
    val pivot: PivotProperties = PivotProperties(),
)

/**
 * Setelan adapter Pivot non-rahasia. [redirectBaseUrl] WAJIB diisi bila Pivot aktif — mode REDIRECT
 * mengharuskan URL balik success/failure/expiration; ketiganya diturunkan dari basis ini (mis.
 * `<base>/paid`). Kosong = charge Pivot gagal dengan pesan jelas (bukan mengirim URL cacat).
 * Lingkungan (sandbox vs produksi) TIDAK di sini melainkan di `pivot_master_config`.
 */
data class PivotProperties(
    val redirectBaseUrl: String = "",
)

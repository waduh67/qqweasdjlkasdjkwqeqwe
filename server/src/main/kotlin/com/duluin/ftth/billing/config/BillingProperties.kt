package com.duluin.ftth.billing.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Kebijakan penagihan tenant (nilai default dev; ditimpa lewat environment di prod).
 *
 * [webhookSecret] adalah rahasia verifikasi callback gateway — WAJIB diisi via env
 * di produksi dan tidak pernah ikut ter-commit dengan nilai asli.
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
    /** Kredensial MASTER agregator platform (mode PLATFORM). Nonaktif secara default. */
    val platform: PlatformGatewayProperties = PlatformGatewayProperties(),
)

/**
 * Kredensial MASTER akun agregator platform (mode PLATFORM). Sengaja di config/env — BUKAN
 * per baris tenant — agar charge/callback tak perlu membaca lintas-RLS. [enabled] mati =
 * semua tenant mode PLATFORM dorman (jatuh ke fallback MANUAL) sampai platform mengonfigurasi.
 */
data class PlatformGatewayProperties(
    val enabled: Boolean = false,
    val xendit: PlatformXenditProperties = PlatformXenditProperties(),
)

/**
 * Akun master Xendit xenPlatform. [secretKey] dipakai basic-auth semua charge PLATFORM &
 * pembuatan sub-account; [feeRuleId] dipasang di header `with-fee-rule` (komisi platform);
 * [callbackBaseUrl] basis URL publik untuk mendaftarkan callback sub-account.
 */
data class PlatformXenditProperties(
    val secretKey: String = "",
    val webhookToken: String = "",
    val feeRuleId: String = "",
    val callbackBaseUrl: String = "",
)

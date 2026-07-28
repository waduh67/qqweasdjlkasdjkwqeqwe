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
    /** Otomatis isolir langganan saat tagihannya menunggak. */
    val autoIsolir: Boolean = true,
    /** Prorata tagihan pertama saat langganan aktif di tengah bulan (default global). */
    val prorateOnActivation: Boolean = false,
    val numberPrefix: String = "INV",
    val defaultProvider: String = "MANUAL",
    /** Selang jalannya scheduler penagihan (ISO-8601 duration). */
    val schedulerInterval: String = "PT12H",
    val webhookSecret: String = "dev-only-billing-webhook-secret-change-me",
)

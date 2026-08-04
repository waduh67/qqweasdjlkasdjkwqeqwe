package com.duluin.ftth.iam

import java.math.BigDecimal
import java.util.UUID

/**
 * Dipublikasikan iam saat sebuah tenant baru selesai di-onboard.
 *
 * Provisioning langganan SaaS-nya (module `platformbilling`) sengaja dipisah lewat event,
 * BUKAN pemanggilan port langsung: iam tak boleh bergantung pada platformbilling secara
 * statis — arah itu menutup siklus modul `iam → platformbilling → billing → … → iam`
 * (ditegakkan [ModularityTests]). platformbilling mendengarkan event ini untuk membuat
 * langganan (idempotent); backfill saat start-up menambal bila listener sempat gagal.
 *
 * Diletakkan di base package iam — permukaan publiknya — karena hanya iam yang
 * menerbitkannya dan consumer memang boleh bergantung pada iam.
 *
 * @param monthlyFeeOverride harga khusus dari super-admin saat onboarding; null = pakai
 *   harga default global ([com.duluin.ftth.platformbilling] `PlatformSetting`).
 */
data class TenantOnboardedEvent(
    val tenantId: UUID,
    val monthlyFeeOverride: BigDecimal?,
)

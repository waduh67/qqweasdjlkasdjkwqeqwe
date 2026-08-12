package com.duluin.ftth.iam

import java.util.UUID

/**
 * Dipublikasikan iam saat admin AWAL sebuah tenant baru saja dibuat — sekali seumur tenant.
 *
 * Berbeda dari [TenantOnboardedEvent] yang terbit setiap kali onboarding dijalankan (idempoten
 * terhadap slug), yang ini hanya terbit bila adminnya sungguh BARU. Onboarding yang diulang
 * untuk tenant yang sama tak boleh mengirimi orang yang sama surat "selamat datang" kedua.
 *
 * Konsumennya module `notification`, yang mengirim email selamat datang berisi kode ISP.
 * Lewat event, bukan panggilan [com.duluin.ftth.notification.NotificationApi] langsung:
 * `notification → billing → iam` sudah ada, jadi `iam → notification` menutup siklus modul
 * (ditegakkan `ModularityTests`). Arah sebaliknya — notification mendengarkan iam — aman.
 *
 * Diletakkan di base package iam, permukaan publiknya, sebelah [TenantOnboardedEvent].
 *
 * @param tenantSlug kode ISP yang di-assign; INTI dari email selamat datang, karena tanpa
 *   kode ini adminnya tak bisa masuk sama sekali.
 */
data class TenantAdminProvisionedEvent(
    val tenantId: UUID,
    val tenantSlug: String,
    val tenantName: String,
    val adminEmail: String,
    val adminName: String,
)

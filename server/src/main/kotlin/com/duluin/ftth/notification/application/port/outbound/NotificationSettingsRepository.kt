package com.duluin.ftth.notification.application.port.outbound

import com.duluin.ftth.notification.domain.model.NotificationSettings

/**
 * Persistence setelan notifikasi tenant. Satu baris per tenant (RLS + @TenantId
 * menyaring ke tenant aktif), jadi [find] mengembalikan baris tenant tunggal —
 * null bila tenant belum pernah menyetel apa-apa.
 */
interface NotificationSettingsRepository {
    fun find(): NotificationSettings?
    fun save(settings: NotificationSettings): NotificationSettings
}

package com.duluin.ftth.notification.application.port.outbound

import com.duluin.ftth.notification.domain.model.NotificationTrigger
import com.duluin.ftth.notification.domain.model.PlatformEmailSettings
import com.duluin.ftth.notification.domain.model.TenantEmailSettings

/**
 * Persistence setelan email PLATFORM (singleton global, tanpa RLS). [find] null =
 * platform belum pernah menyetel apa pun; pemanggil memakai
 * [PlatformEmailSettings.default] agar pembacaan tak perlu menulis baris lebih dulu.
 */
interface PlatformEmailSettingsRepository {
    fun find(): PlatformEmailSettings?
    fun save(settings: PlatformEmailSettings): PlatformEmailSettings
}

/**
 * Persistence timpaan email milik tenant aktif (satu baris per tenant, disaring RLS).
 * Null = tenant belum menimpa apa pun, jadi seluruhnya mewarisi platform.
 */
interface TenantEmailSettingsRepository {
    fun find(): TenantEmailSettings?
    fun save(settings: TenantEmailSettings): TenantEmailSettings
}

/**
 * Baris subjek per pemicu, dua tingkat. Pemicu yang TAK punya baris memakai subjek
 * bawaan di kode — karena itu antarmuka ini bicara dalam `Map` yang boleh tak lengkap,
 * bukan daftar tetap delapan entri.
 *
 * [replacePlatform]/[replaceTenant] mengganti SELURUH peta: pemicu yang tak disebut
 * barisnya dihapus (kembali ke bawaan). Bentuk "ganti semua" dipilih supaya form yang
 * mengosongkan satu kolom benar-benar berarti "kembalikan ke bawaan", bukan "biarkan".
 */
interface EmailSubjectRepository {
    fun platformSubjects(): Map<NotificationTrigger, String>
    fun tenantSubjects(): Map<NotificationTrigger, String>
    fun replacePlatform(subjects: Map<NotificationTrigger, String>)
    fun replaceTenant(subjects: Map<NotificationTrigger, String>)
}

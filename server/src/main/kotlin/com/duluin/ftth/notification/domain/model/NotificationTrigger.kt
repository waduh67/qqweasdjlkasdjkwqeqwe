package com.duluin.ftth.notification.domain.model

/**
 * Asal-usul sebuah broadcast/pesan: `MANUAL` bila operator menekan kirim sendiri,
 * sisanya bila lahir otomatis dari peristiwa modul lain (langganan, tagihan, WO,
 * insiden).
 *
 * Dipakai dua arah:
 *  - menandai baris riwayat [Broadcast] agar operator tahu kenapa pesan terkirim, dan
 *  - dipetakan ke saklar on/off di [NotificationSettings] lewat [NotificationSettings.isTriggerEnabled],
 *    sehingga tenant bisa mematikan satu jenis pemicu tanpa mengganggu yang lain.
 *
 * `MANUAL` tak pernah bisa dimatikan — operator yang menekan kirim selalu berwenang.
 */
enum class NotificationTrigger {
    MANUAL,
    SUBSCRIPTION_ACTIVATED,
    SUBSCRIPTION_ISOLATED,
    SUBSCRIPTION_TERMINATED,
    INVOICE_DUE_SOON,
    INVOICE_OVERDUE,
    WORK_ORDER_SCHEDULED,
    INCIDENT_OPENED,
}

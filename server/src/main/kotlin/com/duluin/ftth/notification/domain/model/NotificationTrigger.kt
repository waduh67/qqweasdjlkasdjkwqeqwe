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
 * `PORTAL_PASSWORD_RESET` juga tidak, dengan alasan berbeda: lihat di bawah.
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

    /**
     * Kode pemulihan password portal pelanggan.
     *
     * Berbeda dari pemicu lain, yang ini TIDAK melewati
     * [com.duluin.ftth.notification.application.service.NotificationSender]: isi pesannya
     * mengandung kode rahasia, sedangkan riwayat broadcast dirancang untuk dibaca operator.
     * Kehadirannya di sini semata agar ISP bisa MEMETAKAN template WhatsApp yang sudah
     * disetujui (Meta/Qontak menolak teks bebas untuk pesan begini), dan agar baris audit
     * pengirimannya sekelas dengan pemicu lain.
     */
    PORTAL_PASSWORD_RESET,
}

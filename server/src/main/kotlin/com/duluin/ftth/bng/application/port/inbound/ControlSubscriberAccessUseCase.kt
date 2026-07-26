package com.duluin.ftth.bng.application.port.inbound

import java.util.UUID

/**
 * Kendali jaringan atas akun PPPoE — jalur tulis ke BRAS (Phase 7c). Berbeda dari
 * [ManageSubscriberAccessUseCase] yang murni data, operasi di sini mengantre perintah
 * nyata ke collector (memutus/mengubah sesi) di samping mengubah status.
 */
interface ControlSubscriberAccessUseCase {

    /**
     * Isolir: potong akses internet pelanggan. Status akun → ISOLATED (mengeluarkannya
     * dari sesi yang diharapkan online) sekaligus mengantre DISCONNECT agar sesi hidup
     * benar-benar terputus.
     */
    fun isolate(id: UUID): SubscriberAccessView

    /**
     * Pulihkan dari isolir. Status akun → ACTIVE; sesi berikutnya akan re-auth dan
     * mengambil profil aktif kembali.
     */
    fun restore(id: UUID): SubscriberAccessView

    /**
     * Reset Login: putus sesi PPPoE agar CPE dial ulang dan re-autentikasi (mengambil
     * profil terkini) — tanpa mengubah status akun. Berguna saat sesi "nyangkut".
     */
    fun resetLogin(id: UUID): SubscriberAccessView
}

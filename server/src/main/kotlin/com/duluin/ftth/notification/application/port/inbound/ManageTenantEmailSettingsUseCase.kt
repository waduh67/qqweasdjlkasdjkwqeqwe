package com.duluin.ftth.notification.application.port.inbound

import com.duluin.ftth.common.storage.StoredObject
import com.duluin.ftth.notification.domain.model.NotificationTrigger

/**
 * Sisi tenant atas email keluar: nama pengirim ([TenantEmailSettingsView.fromName]), alamat
 * balasan ([TenantEmailSettingsView.replyToAddress]), bungkus merek, dan baris subjek per
 * pemicu — semuanya sebagai TIMPAAN atas setelan platform. Kosong berarti mewarisi, bukan
 * mengosongkan.
 *
 * Dua hal sengaja TAK ada di sini: sambungan SMTP dan alamat pengirim. Relay-nya milik
 * platform, dan relay itu hanya menerima pengirim yang sudah terverifikasi di sisi penyedia —
 * alamat berdomain tenant membuat suratnya ditolak sebelum berangkat. Alamat platform yang
 * berlaku tetap dikirim ke UI lewat [TenantEmailSettingsView.platformFromAddress] supaya
 * operator tahu apa yang dilihat pelanggannya, bukan supaya bisa diubah.
 */
interface ManageTenantEmailSettingsUseCase {
    fun get(): TenantEmailSettingsView
    fun update(command: UpdateTenantEmailSettingsCommand): TenantEmailSettingsView

    /** Simpan/ganti logo tenant (menimpa logo platform di email tenant ini). */
    fun uploadLogo(contentType: String, bytes: ByteArray): TenantEmailSettingsView

    /** Kembalikan ke logo bawaan platform (byte tenant dihapus dari storage). */
    fun deleteLogo(): TenantEmailSettingsView

    /** Byte logo tenant untuk disajikan; null bila tenant tak menimpa logo. */
    fun getLogo(): StoredObject?

    fun sendTest(to: String): EmailTestResultView

    /** HTML pratinjau memakai bungkus yang SUDAH tergabung (platform ditimpa tenant). */
    fun preview(): String
}

/**
 * Timpaan tenant beserta nilai warisannya. Tiap field punya pasangan `inherited*` supaya
 * layar bisa menampilkan warisan platform sebagai placeholder — tanpa itu operator tak
 * punya cara tahu apa yang sebenarnya terkirim saat kolomnya dibiarkan kosong.
 */
@Suppress("LongParameterList")
data class TenantEmailSettingsView(
    /** Alamat balasan tenant; null = surat berangkat tanpa `Reply-To`. */
    val replyToAddress: String?,
    val fromName: String?,
    /** Tenant punya logo sendiri atau tidak; false = memakai logo platform. */
    val logoSet: Boolean,
    val accentColor: String?,
    val footerText: String?,
    val signatureText: String?,
    // Nilai yang benar-benar berlaku setelah pewarisan diselesaikan (untuk placeholder).
    /**
     * Alamat `From` yang berlaku — bukan "warisan" seperti tetangganya melainkan nilai
     * TERKUNCI: tak ada kolom di sini yang bisa menimpanya. Namanya sengaja tak berawalan
     * `inherited` supaya layar tak menjanjikan timpaan yang diam-diam diabaikan server.
     */
    val platformFromAddress: String?,
    val inheritedFromName: String,
    val effectiveLogoUrl: String?,
    val inheritedAccentColor: String?,
    val inheritedFooterText: String?,
    val inheritedSignatureText: String?,
    val subjects: List<EmailSubjectView>,
)

/**
 * Perubahan timpaan tenant. Semua nullable — null/kosong berarti "hapus timpaan, warisi
 * platform lagi". Logo tak lewat sini: ia hanya berubah lewat unggah/hapus.
 */
data class UpdateTenantEmailSettingsCommand(
    val replyToAddress: String?,
    val fromName: String?,
    val accentColor: String?,
    val footerText: String?,
    val signatureText: String?,
    val subjects: Map<NotificationTrigger, String>,
)

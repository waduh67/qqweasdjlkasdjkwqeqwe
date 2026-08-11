package com.duluin.ftth.notification.application.port.inbound

import com.duluin.ftth.common.storage.StoredObject
import com.duluin.ftth.notification.domain.model.NotificationTrigger

/**
 * Sisi super-admin platform atas email keluar: sambungan SMTP, identitas pengirim bawaan,
 * bungkus merek bawaan (logo/warna/footer/tanda tangan), dan baris subjek per pemicu.
 *
 * Semua yang disetel di sini berlaku sebagai BAWAAN yang diwarisi seluruh tenant; tenant
 * hanya boleh menimpa identitas & tampilannya lewat [ManageTenantEmailSettingsUseCase].
 * Sambungan SMTP-nya sengaja tak bisa ditimpa: relay itu milik platform dan reputasi
 * pengirimannya ditanggung bersama semua tenant sekaligus.
 */
interface ManagePlatformEmailSettingsUseCase {
    fun get(): PlatformEmailSettingsView
    fun update(command: UpdatePlatformEmailSettingsCommand): PlatformEmailSettingsView

    /** Simpan/ganti logo bawaan email (byte ke object storage). */
    fun uploadLogo(contentType: String, bytes: ByteArray): PlatformEmailSettingsView

    /** Lepas logo bawaan (hapus dari storage); email berjalan tanpa logo. */
    fun deleteLogo(): PlatformEmailSettingsView

    /** Byte logo bawaan untuk disajikan; null bila belum ada. */
    fun getLogo(): StoredObject?

    /** Kirim satu email percobaan ke [to] memakai setelan yang TERSIMPAN saat ini. */
    fun sendTest(to: String): EmailTestResultView

    /** HTML pratinjau memakai bungkus platform — dirender jalur yang sama dengan email sungguhan. */
    fun preview(): String
}

/**
 * Setelan email platform untuk ditampilkan. Password SMTP write-only: yang keluar hanya
 * penanda [smtpPasswordSet], tak pernah nilainya.
 *
 * [smtpConfigured] false berarti pengiriman jatuh ke `spring.mail.*` dari env — bukan
 * berarti email mati. Perbedaan itu perlu terlihat di layar, karena "host kosong" gampang
 * disalahartikan sebagai kesalahan konfigurasi padahal itu justru keadaan bawaan.
 */
@Suppress("LongParameterList")
data class PlatformEmailSettingsView(
    val smtpHost: String?,
    val smtpPort: Int,
    val smtpUsername: String?,
    val smtpPasswordSet: Boolean,
    val smtpAuth: Boolean,
    val smtpStartTls: Boolean,
    val smtpConfigured: Boolean,
    val fromAddress: String?,
    val fromName: String,
    val logoSet: Boolean,
    /** URL absolut logo bila sudah bisa dirangkai; null = base URL belum disetel / tak ada logo. */
    val logoUrl: String?,
    val accentColor: String?,
    val footerText: String?,
    val signatureText: String?,
    val publicBaseUrl: String?,
    val subjects: List<EmailSubjectView>,
)

/**
 * Satu baris subjek pemicu. [subject] = timpaan yang benar-benar tersimpan di tingkat ini
 * (null = tak menimpa), [inheritedSubject] = yang akan terpakai bila dibiarkan kosong.
 *
 * Dua field, bukan satu nilai efektif: form yang cuma menampilkan hasil akhir membuat
 * operator tak bisa membedakan "saya menyetelnya begitu" dari "itu memang bawaannya",
 * sehingga tak pernah tahu kolom mana yang aman dikosongkan.
 */
data class EmailSubjectView(
    val trigger: NotificationTrigger,
    val subject: String?,
    val inheritedSubject: String,
)

/** Hasil kirim uji apa adanya — detail transport ditampilkan mentah supaya bisa didiagnosa. */
data class EmailTestResultView(
    val delivered: Boolean,
    val detail: String,
)

/**
 * Perubahan setelan platform. [smtpPassword] null/kosong = biarkan yang tersimpan (menyunting
 * footer tak boleh diam-diam menghapus kredensial SMTP). [subjects] mengganti SELURUH peta
 * timpaan: pemicu yang tak disebut kembali memakai subjek bawaan di kode.
 */
@Suppress("LongParameterList")
data class UpdatePlatformEmailSettingsCommand(
    val smtpHost: String?,
    val smtpPort: Int,
    val smtpUsername: String?,
    val smtpPassword: String?,
    val smtpAuth: Boolean,
    val smtpStartTls: Boolean,
    val fromAddress: String?,
    val fromName: String?,
    val accentColor: String?,
    val footerText: String?,
    val signatureText: String?,
    val publicBaseUrl: String?,
    val subjects: Map<NotificationTrigger, String>,
)

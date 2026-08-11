package com.duluin.ftth.notification.domain.model

import com.duluin.ftth.common.domain.UuidV7
import com.duluin.ftth.common.domain.error.ValidationException
import java.util.UUID

/**
 * Bungkus merek sebuah email: logo, warna aksen, footer, dan tanda tangan. Dipakai
 * BERSAMA oleh setelan platform (sebagai bawaan) dan setelan tenant (sebagai timpaan),
 * karena aturan pewarisannya cuma satu dan sebaiknya tinggal di satu tempat saja:
 * [overriddenBy].
 *
 * Semua field nullable, dan null di sini selalu berarti "belum diisi / ikut bawaan" —
 * bukan "kosongkan". Konsekuensinya tenant tak bisa MENGHAPUS footer platform lewat
 * pengosongan field; yang bisa dilakukan adalah menggantinya dengan teks lain. Itu
 * disengaja: menyembunyikan identitas penyedia bukan wewenang tenant.
 */
data class EmailBranding(
    /** Object-storage key logo. Berpasangan dengan [logoContentType] — keduanya ikut/tidak bersama. */
    val logoStorageKey: String?,
    /** MIME logo (mis. `image/png`), agar endpoint publik menyajikan byte dengan tipe benar. */
    val logoContentType: String?,
    /** Warna aksen `#RRGGBB` untuk garis kepala & tautan di badan HTML. */
    val accentColor: String?,
    /** Baris kecil paling bawah (alamat kantor, catatan "email otomatis, mohon tak dibalas"). */
    val footerText: String?,
    /** Penutup di atas footer, mis. "Salam, Tim Dukungan Duluin.net". */
    val signatureText: String?,
) {
    /** Apakah ada logo terunggah (byte-nya ada di object storage). */
    val logoSet: Boolean get() = !logoStorageKey.isNullOrBlank()

    /**
     * Gabungkan timpaan [other] di atas nilai ini: field yang diisi [other] menang, yang
     * kosong mewarisi milik ini. Logo diperlakukan sebagai SATU kesatuan (key + MIME)
     * supaya tak pernah lahir kombinasi key tenant dengan MIME platform.
     */
    fun overriddenBy(other: EmailBranding): EmailBranding = EmailBranding(
        logoStorageKey = if (other.logoSet) other.logoStorageKey else logoStorageKey,
        logoContentType = if (other.logoSet) other.logoContentType else logoContentType,
        accentColor = other.accentColor ?: accentColor,
        footerText = other.footerText ?: footerText,
        signatureText = other.signatureText ?: signatureText,
    )

    /** Salinan tanpa logo — dipakai saat tenant "kembalikan ke bawaan". */
    fun withoutLogo(): EmailBranding = copy(logoStorageKey = null, logoContentType = null)

    /** Salinan dengan logo baru yang byte-nya SUDAH tersimpan di object storage. */
    fun withLogo(storageKey: String, contentType: String): EmailBranding = copy(
        logoStorageKey = storageKey.trim().takeIf { it.isNotEmpty() }
            ?: throw ValidationException("Storage key logo email kosong"),
        logoContentType = contentType.trim().takeIf { it.isNotEmpty() } ?: "application/octet-stream",
    )

    companion object {
        val EMPTY = EmailBranding(null, null, null, null, null)

        private const val MAX_FOOTER = 500
        private const val MAX_SIGNATURE = 200
        private val HEX_COLOR = Regex("^#[0-9a-fA-F]{6}$")

        /**
         * Bentuk tervalidasi dari masukan operator (logo TIDAK lewat sini — ia datang dari
         * unggahan, bukan dari form teks). String kosong dinormalkan jadi null.
         */
        fun of(accentColor: String?, footerText: String?, signatureText: String?): EmailBranding =
            EmailBranding(
                logoStorageKey = null,
                logoContentType = null,
                accentColor = validateColor(accentColor),
                footerText = validateText(footerText, MAX_FOOTER, "Teks footer email"),
                signatureText = validateText(signatureText, MAX_SIGNATURE, "Tanda tangan email"),
            )

        private fun validateColor(value: String?): String? {
            val trimmed = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            if (!HEX_COLOR.matches(trimmed)) throw ValidationException("Warna aksen harus berformat #RRGGBB")
            return trimmed.lowercase()
        }

        private fun validateText(value: String?, max: Int, label: String): String? {
            val trimmed = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            if (trimmed.length > max) throw ValidationException("$label maksimal $max karakter")
            return trimmed
        }
    }
}

/**
 * Sambungan SMTP siap-pakai (sudah terdekripsi) untuk membangun `JavaMailSender`. Hanya
 * lahir bila host terisi; kalau tidak, pemanggil jatuh ke `spring.mail.*` dari env.
 */
data class SmtpTransport(
    val host: String,
    val port: Int,
    val username: String?,
    val password: String?,
    val auth: Boolean,
    val startTls: Boolean,
)

/**
 * Setelan email milik PLATFORM (satu baris global, tanpa RLS — pola `PivotMasterConfig`
 * di module billing): sambungan SMTP, identitas pengirim bawaan, tampilan bawaan, dan URL
 * publik aplikasi untuk merangkai `<img src>` logo.
 *
 * [smtpPassword] plaintext di sini, ciphertext di DB — batas enkripsinya di adapter
 * persistence. Pada [update], password null/kosong berarti "biarkan apa adanya" supaya
 * menyunting footer tak diam-diam menghapus kredensial SMTP.
 *
 * Host kosong bukan kondisi galat: artinya platform belum (atau sengaja tak) memindahkan
 * setelan SMTP ke DB, dan pengiriman jatuh ke `spring.mail.*` dari env seperti sebelum
 * fitur ini ada.
 */
@Suppress("LongParameterList")
class PlatformEmailSettings private constructor(
    val id: UUID,
    smtpHost: String?,
    smtpPort: Int,
    smtpUsername: String?,
    smtpPassword: String?,
    smtpAuth: Boolean,
    smtpStartTls: Boolean,
    fromAddress: String?,
    fromName: String,
    branding: EmailBranding,
    publicBaseUrl: String?,
) {
    var smtpHost: String? = smtpHost
        private set

    var smtpPort: Int = smtpPort
        private set

    var smtpUsername: String? = smtpUsername
        private set

    /** Plaintext di domain, ciphertext di DB. Null = belum pernah diisi. */
    var smtpPassword: String? = smtpPassword
        private set

    var smtpAuth: Boolean = smtpAuth
        private set

    var smtpStartTls: Boolean = smtpStartTls
        private set

    /** Alamat pengirim bawaan; tenant boleh menimpanya dengan alamatnya sendiri. */
    var fromAddress: String? = fromAddress
        private set

    var fromName: String = fromName
        private set

    var branding: EmailBranding = branding
        private set

    /**
     * URL absolut aplikasi (mis. `https://app.duluin.net`) untuk menyusun tautan logo.
     * Klien email tak mengerti path relatif, jadi tanpa ini email tetap terkirim — hanya
     * tanpa logo. Kosong di sini = jatuh ke `ftth.mail.public-base-url` dari env.
     */
    var publicBaseUrl: String? = publicBaseUrl
        private set

    /** Untuk view: password sudah tersimpan atau belum, tanpa membocorkan isinya. */
    val smtpPasswordSet: Boolean get() = !smtpPassword.isNullOrBlank()

    /** Setelan SMTP dari DB dipakai atau tidak. False = pengiriman memakai env. */
    val smtpConfigured: Boolean get() = !smtpHost.isNullOrBlank()

    @Suppress("LongParameterList")
    fun update(
        smtpHost: String?,
        smtpPort: Int,
        smtpUsername: String?,
        smtpPassword: String?,
        smtpAuth: Boolean,
        smtpStartTls: Boolean,
        fromAddress: String?,
        fromName: String?,
        branding: EmailBranding,
        publicBaseUrl: String?,
    ) {
        this.smtpHost = validateHost(smtpHost)
        this.smtpPort = validatePort(smtpPort)
        this.smtpUsername = validateLength(smtpUsername, MAX_USERNAME, "Username SMTP")
        // Null/kosong = biarkan apa adanya, agar rahasia tak terhapus saat menyunting field lain.
        smtpPassword?.trim()?.takeIf { it.isNotEmpty() }?.let {
            this.smtpPassword = validateLength(it, MAX_PASSWORD, "Password SMTP")
        }
        this.smtpAuth = smtpAuth
        this.smtpStartTls = smtpStartTls
        this.fromAddress = validateEmail(fromAddress, "Alamat pengirim")
        this.fromName = fromName?.trim()?.takeIf { it.isNotEmpty() }?.let {
            validateLength(it, MAX_FROM_NAME, "Nama pengirim")!!
        } ?: DEFAULT_FROM_NAME
        // Logo tak ikut form teks: ia hanya berubah lewat attachLogo/clearLogo.
        this.branding = branding.copy(
            logoStorageKey = this.branding.logoStorageKey,
            logoContentType = this.branding.logoContentType,
        )
        this.publicBaseUrl = validateBaseUrl(publicBaseUrl)
    }

    /** Pasang (atau ganti) logo yang byte-nya sudah tersimpan di object storage. */
    fun attachLogo(storageKey: String, contentType: String) {
        branding = branding.withLogo(storageKey, contentType)
    }

    /** Lepas logo (byte-nya dihapus dari storage oleh pemanggil). */
    fun clearLogo() {
        branding = branding.withoutLogo()
    }

    /**
     * Sambungan SMTP siap-pakai, atau null bila host belum diisi — null berarti "pakai
     * setelan env", bukan "jangan kirim".
     */
    fun resolveSmtp(): SmtpTransport? {
        val host = smtpHost?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return SmtpTransport(
            host = host,
            port = smtpPort,
            username = smtpUsername?.trim()?.takeIf { it.isNotEmpty() },
            password = smtpPassword?.takeIf { it.isNotEmpty() },
            auth = smtpAuth,
            startTls = smtpStartTls,
        )
    }

    companion object {
        const val DEFAULT_FROM_NAME = "NetOps Console"
        const val DEFAULT_SMTP_PORT = 587
        private const val MAX_HOST = 255
        private const val MAX_USERNAME = 255
        private const val MAX_PASSWORD = 512
        private const val MAX_FROM_NAME = 100
        private const val MAX_BASE_URL = 300
        private const val MIN_PORT = 1
        private const val MAX_PORT = 65535

        /** Baris bawaan saat platform belum menyetel apa pun — SMTP kosong, tampilan kosong. */
        fun default(): PlatformEmailSettings = PlatformEmailSettings(
            id = UuidV7.generate(),
            smtpHost = null,
            smtpPort = DEFAULT_SMTP_PORT,
            smtpUsername = null,
            smtpPassword = null,
            smtpAuth = true,
            smtpStartTls = true,
            fromAddress = null,
            fromName = DEFAULT_FROM_NAME,
            branding = EmailBranding.EMPTY,
            publicBaseUrl = null,
        )

        @Suppress("LongParameterList")
        fun rehydrate(
            id: UUID,
            smtpHost: String?,
            smtpPort: Int,
            smtpUsername: String?,
            smtpPassword: String?,
            smtpAuth: Boolean,
            smtpStartTls: Boolean,
            fromAddress: String?,
            fromName: String,
            branding: EmailBranding,
            publicBaseUrl: String?,
        ): PlatformEmailSettings = PlatformEmailSettings(
            id, smtpHost, smtpPort, smtpUsername, smtpPassword, smtpAuth, smtpStartTls,
            fromAddress, fromName, branding, publicBaseUrl,
        )

        private fun validateHost(value: String?): String? =
            validateLength(value, MAX_HOST, "Host SMTP")

        private fun validatePort(port: Int): Int {
            if (port !in MIN_PORT..MAX_PORT) throw ValidationException("Port SMTP harus antara $MIN_PORT dan $MAX_PORT")
            return port
        }

        private fun validateBaseUrl(value: String?): String? {
            val trimmed = validateLength(value, MAX_BASE_URL, "URL publik aplikasi") ?: return null
            if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
                throw ValidationException("URL publik aplikasi harus diawali http:// atau https://")
            }
            return trimmed.trimEnd('/')
        }

        internal fun validateLength(value: String?, max: Int, label: String): String? {
            val trimmed = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            if (trimmed.length > max) throw ValidationException("$label maksimal $max karakter")
            return trimmed
        }

        /**
         * Validasi longgar dan disengaja: cukup memastikan bentuknya `sesuatu@sesuatu.tld`
         * tanpa spasi. Aturan alamat email sebenarnya jauh lebih permisif daripada regex
         * mana pun, dan yang benar-benar menolak alamat salah adalah server SMTP-nya.
         */
        internal fun validateEmail(value: String?, label: String): String? {
            val trimmed = validateLength(value, MAX_EMAIL, label) ?: return null
            if (!EMAIL.matches(trimmed)) throw ValidationException("$label tidak berformat alamat email yang sah")
            return trimmed
        }

        private const val MAX_EMAIL = 254
        private val EMAIL = Regex("^[^@\\s]+@[^@\\s.]+(\\.[^@\\s.]+)+$")
    }
}

/**
 * Timpaan setelan email milik satu TENANT. Semua field nullable: null = warisi platform.
 *
 * Yang boleh ditimpa tenant sengaja terbatas pada IDENTITAS ([fromAddress], [fromName])
 * dan TAMPILAN ([branding]) — bukan sambungan SMTP-nya, karena relay itu milik platform
 * dan reputasi pengirimannya ditanggung bersama semua tenant.
 *
 * Catatan penting soal [fromAddress]: alamat ini dipakai apa adanya sebagai `From`, plus
 * `Reply-To`. Relay platform belum tentu berwenang atas domain tenant, jadi tanpa SPF/DKIM
 * yang mengizinkannya email berisiko masuk spam atau ditolak. Peringatan itu ditampilkan
 * di UI; keputusannya tetap di tangan tenant.
 */
class TenantEmailSettings private constructor(
    val id: UUID,
    val tenantId: UUID,
    fromAddress: String?,
    fromName: String?,
    branding: EmailBranding,
) {
    var fromAddress: String? = fromAddress
        private set

    var fromName: String? = fromName
        private set

    var branding: EmailBranding = branding
        private set

    fun update(fromAddress: String?, fromName: String?, branding: EmailBranding) {
        this.fromAddress = PlatformEmailSettings.validateEmail(fromAddress, "Alamat pengirim")
        this.fromName = PlatformEmailSettings.validateLength(fromName, MAX_FROM_NAME, "Nama pengirim")
        // Logo hanya berubah lewat attachLogo/clearLogo, tak ikut form teks.
        this.branding = branding.copy(
            logoStorageKey = this.branding.logoStorageKey,
            logoContentType = this.branding.logoContentType,
        )
    }

    fun attachLogo(storageKey: String, contentType: String) {
        branding = branding.withLogo(storageKey, contentType)
    }

    /** Kembalikan ke logo bawaan platform (byte tenant dihapus dari storage oleh pemanggil). */
    fun clearLogo() {
        branding = branding.withoutLogo()
    }

    companion object {
        private const val MAX_FROM_NAME = 100

        /** Baris bawaan tenant yang belum menimpa apa pun — seluruhnya mewarisi platform. */
        fun defaultFor(tenantId: UUID): TenantEmailSettings = TenantEmailSettings(
            id = UuidV7.generate(),
            tenantId = tenantId,
            fromAddress = null,
            fromName = null,
            branding = EmailBranding.EMPTY,
        )

        fun rehydrate(
            id: UUID,
            tenantId: UUID,
            fromAddress: String?,
            fromName: String?,
            branding: EmailBranding,
        ): TenantEmailSettings = TenantEmailSettings(id, tenantId, fromAddress, fromName, branding)
    }
}

package com.duluin.ftth.portal.application.port.inbound

import java.time.Instant
import java.util.UUID

/**
 * Autentikasi realm PORTAL pelanggan. Berbeda dari `AuthenticationUseCase` operator yang
 * memakai email global: identitas pelanggan (email, nomor HP, atau username) BOLEH kembar
 * antar-ISP, karena satu orang bisa berlangganan di dua tempat.
 *
 * Dulu perbedaan itu diselesaikan dengan meminta pelanggan mengetik "kode ISP" di layar
 * masuk — pertanyaan yang tak bisa dijawab siapa pun yang tak pernah membaca dokumen
 * internal ISP-nya. Sekarang server yang mencari sendiri lewat indeks identitas, dan tenant
 * baru ditanyakan pada kasus yang benar-benar kembar (lihat [PortalLoginResult.ChooseTenant]).
 */
interface PortalAuthenticationUseCase {

    fun login(command: PortalLoginCommand): PortalLoginResult

    fun refresh(refreshToken: String): PortalAuthTokens

    fun logout(refreshToken: String)
}

/**
 * Apa yang diketik pelanggan pada layar masuk: SATU kotak identitas + password.
 *
 * [tenantSlug] hanya terisi pada langkah kedua, setelah server sendiri yang memberitahu
 * bahwa identitas ini dipakai di lebih dari satu ISP. Pelanggan tak pernah diminta
 * mengarangnya. Tetap diterima juga dari tautan ber-`?tenant=` yang dibagikan ISP.
 */
data class PortalLoginCommand(
    val identifier: String,
    val password: String,
    val tenantSlug: String? = null,
)

/**
 * Hasil percobaan masuk. Dua kemungkinan, dan yang kedua jarang.
 *
 * [ChooseTenant] SENGAJA hanya muncul SETELAH password terbukti benar. Menawarkan daftar
 * ISP lebih dulu akan mengubah layar masuk jadi alat intip: siapa pun bisa mengetik nomor HP
 * orang lain dan mengetahui ia berlangganan di mana. Karena pilihan baru ditampilkan kepada
 * orang yang sudah membuktikan tahu passwordnya, tak ada yang bocor.
 */
sealed interface PortalLoginResult {

    data class Authenticated(val tokens: PortalAuthTokens) : PortalLoginResult

    data class ChooseTenant(val choices: List<PortalTenantChoice>) : PortalLoginResult
}

/** Satu ISP yang bisa dimasuki dengan identitas & password yang barusan diketik. */
data class PortalTenantChoice(
    val tenantSlug: String,
    val tenantName: String,
)

/** Pasangan token + profil pelanggan — dikembalikan saat login & refresh berhasil. */
data class PortalAuthTokens(
    val accessToken: String,
    val accessTokenExpiresAt: Instant,
    val refreshToken: String,
    val refreshTokenExpiresAt: Instant,
    val customer: PortalProfileView,
)

/**
 * Profil pelanggan portal untuk respons auth (& endpoint /me). Sengaja ringkas: identitas
 * + status; data kaya (tagihan, koneksi, paket) dilayani query self-service terpisah.
 */
data class PortalProfileView(
    val customerId: UUID,
    val tenantId: UUID,
    val tenantSlug: String,
    val code: String,
    val name: String,
    val login: String,
    val phone: String?,
    val status: String,
)

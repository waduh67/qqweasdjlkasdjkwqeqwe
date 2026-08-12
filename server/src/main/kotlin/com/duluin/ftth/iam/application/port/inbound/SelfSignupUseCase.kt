package com.duluin.ftth.iam.application.port.inbound

/**
 * Pendaftaran mandiri (self-signup) ISP baru — jalur PUBLIK tanpa platform admin.
 *
 * Berbeda dari [OnboardTenantUseCase] yang dipakai platform admin dan bersifat
 * IDEMPOTEN terhadap slug: pendaftaran publik WAJIB menghasilkan tenant BARU. Kalau tidak,
 * memakai ulang slug yang ada akan menyuntikkan admin baru ke tenant milik orang lain
 * (pengambilalihan) atau membuat tenant yatim tanpa admin. Karena itu use case ini yang
 * MEMILIH kode ISP-nya sendiri dan menjaga keunikan email di depan, lalu mendelegasikan
 * pembuatan ke [OnboardTenantUseCase] (langganan trial + role + admin ikut otomatis).
 */
interface SelfSignupUseCase {

    fun signup(command: SelfSignupCommand): SelfSignupResult
}

/**
 * Tanpa `slug`: kode ISP dipilih server dari [name]. Kode itu kunci teknis, bukan keputusan
 * bisnis — meminta pendaftar mengarangnya hanya menghasilkan bentrok 409 di tengah alur untuk
 * sesuatu yang tak ia pedulikan.
 */
data class SelfSignupCommand(
    val name: String,
    val adminEmail: String,
    val adminName: String,
    val adminPassword: String,
)

/**
 * Hasil pendaftaran — sengaja minimal, tak membocorkan id/detail internal tenant. [slug] WAJIB
 * ikut: pendaftar tak pernah mengetikkannya, sementara layar masuk staf memintanya setiap kali.
 */
data class SelfSignupResult(
    val slug: String,
    val name: String,
    val adminEmail: String,
)

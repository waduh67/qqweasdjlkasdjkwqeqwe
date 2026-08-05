package com.duluin.ftth.iam.application.port.inbound

/**
 * Pendaftaran mandiri (self-signup) ISP baru — jalur PUBLIK tanpa platform admin.
 *
 * Berbeda dari [OnboardTenantUseCase] yang dipakai platform admin dan bersifat
 * IDEMPOTEN terhadap slug: pendaftaran publik WAJIB menolak slug/email yang sudah
 * dipakai. Kalau tidak, memakai ulang slug yang ada akan menyuntikkan admin baru ke
 * tenant milik orang lain (pengambilalihan) atau membuat tenant yatim tanpa admin.
 * Karena itu use case ini menjaga keunikan di depan, lalu mendelegasikan pembuatan
 * ke [OnboardTenantUseCase] (langganan trial + role + admin ikut otomatis).
 */
interface SelfSignupUseCase {

    fun signup(command: SelfSignupCommand): SelfSignupResult
}

data class SelfSignupCommand(
    val slug: String,
    val name: String,
    val adminEmail: String,
    val adminName: String,
    val adminPassword: String,
)

/** Hasil pendaftaran — sengaja minimal, tak membocorkan id/detail internal tenant. */
data class SelfSignupResult(
    val slug: String,
    val name: String,
    val adminEmail: String,
)

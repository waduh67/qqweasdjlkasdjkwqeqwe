package com.duluin.ftth.common.domain.error

/**
 * Hierarki error domain — dilempar oleh lapisan domain & application.
 *
 * Sengaja BEBAS dari HTTP/framework: layer web (adapter) yang menerjemahkan
 * ke status code lewat GlobalExceptionHandler. Dengan begitu use case bisa
 * diuji tanpa menyeret Spring MVC.
 */
sealed class DomainException(message: String) : RuntimeException(message)

/** Entitas yang diminta tidak ditemukan (→ 404). */
class NotFoundException(message: String) : DomainException(message)

/** Input melanggar invariant/aturan bisnis (→ 400). */
class ValidationException(message: String) : DomainException(message)

/** Bentrok dengan state yang ada, mis. duplikat unik (→ 409). */
class ConflictException(message: String) : DomainException(message)

/** Gagal autentikasi — kredensial/token tidak valid (→ 401). */
class AuthenticationException(message: String) : DomainException(message)

/**
 * Password sudah benar, tinggal faktor keduanya (→ 401 dengan penanda khusus).
 *
 * SENGAJA bukan turunan [AuthenticationException]: ini bukan percobaan yang gagal
 * melainkan langkah berikutnya, jadi ia tak boleh ikut menghabiskan jatah percobaan di
 * rem anti-tebak — kalau ikut dihitung, setiap login 2FA yang normal memakan jatah dua
 * kali dan orang yang login berulang kali di hari sibuk akan mengunci dirinya sendiri.
 */
class TwoFactorRequiredException(message: String) : DomainException(message)

/** Terautentikasi tapi tidak berwenang atas operasi/data ini (→ 403). */
class AccessDeniedException(message: String) : DomainException(message)

/**
 * Izinnya cukup, tapi langganan SaaS tenant ini menunggak melewati masa tenggang sehingga
 * konsolnya baca-saja (→ 402 dengan penanda `SUBSCRIPTION_LOCKED`).
 *
 * SENGAJA bukan turunan [AccessDeniedException]: bagi pengguna, 403 berarti "minta izin ke
 * admin" — jalan buntu yang salah alamat. Yang ini punya jalan keluar yang jelas dan bisa
 * ditempuh sendiri: bayar tagihan langganannya. Web membedakannya lewat penanda itu untuk
 * mengarahkan ke halaman langganan, bukan menampilkan "akses ditolak".
 */
class SubscriptionLockedException(
    message: String = "Langganan aplikasi menunggak. Konsol dalam mode baca-saja sampai tagihan dilunasi.",
) : DomainException(message)

/**
 * Terlalu sering mencoba (→ 429). [retryAfter] diteruskan ke header `Retry-After` supaya
 * klien tahu harus menunggu berapa lama, bukan sekadar ditolak tanpa penjelasan.
 */
class TooManyRequestsException(
    message: String,
    val retryAfter: java.time.Duration,
) : DomainException(message)

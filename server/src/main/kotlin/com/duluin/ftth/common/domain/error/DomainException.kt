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

/** Terautentikasi tapi tidak berwenang atas operasi/data ini (→ 403). */
class AccessDeniedException(message: String) : DomainException(message)

package com.duluin.ftth.common.infrastructure.web

import com.duluin.ftth.common.domain.error.AccessDeniedException
import com.duluin.ftth.common.domain.error.AuthenticationException
import com.duluin.ftth.common.domain.error.ConflictException
import com.duluin.ftth.common.domain.error.NotFoundException
import com.duluin.ftth.common.domain.error.SubscriptionLockedException
import com.duluin.ftth.common.domain.error.TooManyRequestsException
import com.duluin.ftth.common.domain.error.TwoFactorRequiredException
import com.duluin.ftth.common.domain.error.ValidationException
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.security.access.AccessDeniedException as SpringAccessDeniedException
import org.springframework.security.authorization.AuthorizationDeniedException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.multipart.MaxUploadSizeExceededException

/**
 * Menerjemahkan exception domain (bebas HTTP) menjadi RFC-7807 ProblemDetail.
 * Inilah satu-satunya tempat pemetaan status code, sehingga lapisan domain &
 * application tidak perlu tahu soal HTTP.
 */
@RestControllerAdvice
class GlobalExceptionHandler {

    @ExceptionHandler(NotFoundException::class)
    fun handleNotFound(ex: NotFoundException) = problem(HttpStatus.NOT_FOUND, ex.message)

    @ExceptionHandler(ValidationException::class)
    fun handleValidation(ex: ValidationException) = problem(HttpStatus.BAD_REQUEST, ex.message)

    @ExceptionHandler(ConflictException::class)
    fun handleConflict(ex: ConflictException) = problem(HttpStatus.CONFLICT, ex.message)

    @ExceptionHandler(AuthenticationException::class)
    fun handleAuthentication(ex: AuthenticationException) = problem(HttpStatus.UNAUTHORIZED, ex.message)

    /**
     * Tetap 401, tapi dengan penanda `code` yang bisa dibedakan mesin. Tanpa itu, halaman
     * masuk cuma melihat "401 lagi" dan tak punya cara memutuskan kapan harus menampilkan
     * kolom kode — selain menebak-nebak dari kalimat pesannya.
     */
    @ExceptionHandler(TwoFactorRequiredException::class)
    fun handleTwoFactorRequired(ex: TwoFactorRequiredException): ProblemDetail =
        problem(HttpStatus.UNAUTHORIZED, ex.message).apply { setProperty("code", "TWO_FACTOR_REQUIRED") }

    @ExceptionHandler(AccessDeniedException::class)
    fun handleAccessDenied(ex: AccessDeniedException) = problem(HttpStatus.FORBIDDEN, ex.message)

    /**
     * 402 Payment Required — status yang praktis tak pernah terpakai di web modern, dan justru
     * itu gunanya di sini: ia tak bisa tertukar dengan 403 milik "izinmu kurang". Penandanya
     * mengikuti pola `TWO_FACTOR_REQUIRED`, dan sudah terbaca `ApiError.code` di klien.
     */
    @ExceptionHandler(SubscriptionLockedException::class)
    fun handleSubscriptionLocked(ex: SubscriptionLockedException): ProblemDetail =
        problem(HttpStatus.PAYMENT_REQUIRED, ex.message).apply { setProperty("code", "SUBSCRIPTION_LOCKED") }

    @ExceptionHandler(AuthorizationDeniedException::class, SpringAccessDeniedException::class)
    fun handleSpringDenied(ex: RuntimeException) =
        problem(HttpStatus.FORBIDDEN, "Tidak punya izin untuk operasi ini")

    /**
     * Satu-satunya handler yang menyusun response sendiri: `Retry-After` adalah header,
     * bukan isi body, dan tanpa itu klien cuma tahu "ditolak" tanpa tahu sampai kapan.
     */
    @ExceptionHandler(TooManyRequestsException::class)
    fun handleTooManyRequests(ex: TooManyRequestsException): ResponseEntity<ProblemDetail> {
        val seconds = maxOf(ex.retryAfter.seconds, 1)
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
            .header(HttpHeaders.RETRY_AFTER, seconds.toString())
            .body(problem(HttpStatus.TOO_MANY_REQUESTS, ex.message).apply {
                setProperty("retryAfterSeconds", seconds)
            })
    }

    @ExceptionHandler(MaxUploadSizeExceededException::class)
    fun handleUploadTooLarge(ex: MaxUploadSizeExceededException) =
        problem(HttpStatus.BAD_REQUEST, "Berkas yang diunggah terlalu besar")

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleBeanValidation(ex: MethodArgumentNotValidException): ProblemDetail =
        problem(HttpStatus.BAD_REQUEST, "Validasi gagal").apply {
            setProperty(
                "errors",
                ex.bindingResult.fieldErrors.associate { it.field to (it.defaultMessage ?: "tidak valid") },
            )
        }

    private fun problem(status: HttpStatus, detail: String?): ProblemDetail =
        ProblemDetail.forStatusAndDetail(status, detail ?: status.reasonPhrase)
}

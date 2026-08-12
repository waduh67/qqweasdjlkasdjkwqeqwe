package com.duluin.ftth.iam.adapter.inbound.web

import com.duluin.ftth.common.infrastructure.security.AttemptThrottle
import com.duluin.ftth.iam.application.port.inbound.SelfSignupCommand
import com.duluin.ftth.iam.application.port.inbound.SelfSignupResult
import com.duluin.ftth.iam.application.port.inbound.SelfSignupUseCase
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

/**
 * Pendaftaran mandiri ISP — endpoint PUBLIK (tanpa bearer token; lihat allowlist di
 * SecurityConfig). Membuat tenant + admin awal + langganan trial dalam satu aksi.
 * Sengaja tanpa `@PreAuthorize`/`@SecurityRequirement`: siapa pun boleh mendaftar.
 */
@RestController
@RequestMapping("/api/signup")
@Tag(name = "Signup")
class SignupController(
    private val signup: SelfSignupUseCase,
    private val throttle: AttemptThrottle,
) {
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun register(@Valid @RequestBody request: SignupRequest, http: HttpServletRequest): SignupResponse {
        // Satu tenant baru = satu skema data + langganan trial. Tanpa rem, satu skrip bisa
        // membanjiri platform dengan ISP hantu yang harus disuspensi manual satu per satu.
        throttle.spendSignup(http.remoteAddr)
        return SignupResponse.from(
            signup.signup(
                SelfSignupCommand(
                    name = request.name,
                    adminEmail = request.adminEmail,
                    adminName = request.adminName,
                    adminPassword = request.adminPassword,
                ),
            ),
        )
    }
}

/**
 * Tanpa `slug`: kode ISP dirakit server dari [name] dan dijamin unik. Klien lama yang masih
 * mengirimkannya tak ditolak — kolomnya sekadar diabaikan, dan kode yang berlaku selalu ada di
 * [SignupResponse.slug].
 */
data class SignupRequest(
    @field:NotBlank val name: String,
    @field:Email @field:NotBlank val adminEmail: String,
    @field:NotBlank val adminName: String,
    @field:Size(min = 8, message = "Password minimal 8 karakter") val adminPassword: String,
)

data class SignupResponse(
    /** Kode ISP yang di-assign server. Dibutuhkan setiap kali staf masuk, jadi wajib ditampilkan. */
    val slug: String,
    val name: String,
    val adminEmail: String,
    /** Pesan siap-tampil untuk mengarahkan pengguna ke halaman masuk. */
    val message: String,
) {
    companion object {
        fun from(result: SelfSignupResult) = SignupResponse(
            slug = result.slug,
            name = result.name,
            adminEmail = result.adminEmail,
            message = "ISP \"${result.name}\" berhasil didaftarkan. Kode ISP Anda: ${result.slug} — " +
                "simpan, kode ini dibutuhkan setiap kali masuk bersama email ${result.adminEmail}.",
        )
    }
}

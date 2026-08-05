package com.duluin.ftth.iam.adapter.inbound.web

import com.duluin.ftth.iam.application.port.inbound.SelfSignupCommand
import com.duluin.ftth.iam.application.port.inbound.SelfSignupResult
import com.duluin.ftth.iam.application.port.inbound.SelfSignupUseCase
import io.swagger.v3.oas.annotations.tags.Tag
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
) {
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun register(@Valid @RequestBody request: SignupRequest): SignupResponse =
        SignupResponse.from(
            signup.signup(
                SelfSignupCommand(
                    slug = request.slug,
                    name = request.name,
                    adminEmail = request.adminEmail,
                    adminName = request.adminName,
                    adminPassword = request.adminPassword,
                ),
            ),
        )
}

/**
 * Slug sengaja hanya `@NotBlank` di web (bukan `@Pattern`) agar input seperti "MyISP"
 * bisa dinormalkan (trim + lowercase) di service; format akhir divalidasi domain
 * (`Tenant.create`) → 400 dengan pesan yang jelas bila tetap tak valid.
 */
data class SignupRequest(
    @field:NotBlank val slug: String,
    @field:NotBlank val name: String,
    @field:Email @field:NotBlank val adminEmail: String,
    @field:NotBlank val adminName: String,
    @field:Size(min = 8, message = "Password minimal 8 karakter") val adminPassword: String,
)

data class SignupResponse(
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
            message = "ISP \"${result.name}\" berhasil didaftarkan. Silakan masuk memakai email ${result.adminEmail}.",
        )
    }
}

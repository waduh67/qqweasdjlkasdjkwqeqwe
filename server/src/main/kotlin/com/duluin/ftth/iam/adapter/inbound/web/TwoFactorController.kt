package com.duluin.ftth.iam.adapter.inbound.web

import com.duluin.ftth.iam.application.port.inbound.ManageTwoFactorUseCase
import com.duluin.ftth.iam.application.port.inbound.RecoveryCodesView
import com.duluin.ftth.iam.application.port.inbound.TotpEnrollmentView
import com.duluin.ftth.iam.application.port.inbound.TwoFactorStatusView
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

/**
 * 2FA milik DIRI SENDIRI. Sengaja di bawah `/api/me` dan tanpa `@PreAuthorize`: tak ada
 * izin yang perlu dipunyai untuk mengamankan akunmu sendiri, dan mensyaratkan satu justru
 * berarti sebagian operator tak boleh memasang pengaman.
 */
@RestController
@RequestMapping("/api/me/2fa")
@Tag(name = "Account")
@SecurityRequirement(name = "bearer-jwt")
class TwoFactorController(
    private val twoFactor: ManageTwoFactorUseCase,
) {
    @GetMapping
    fun status(): TwoFactorStatusView = twoFactor.status()

    @PostMapping("/setup")
    fun setup(): TotpEnrollmentView = twoFactor.startEnrollment()

    @PostMapping("/enable")
    fun enable(@Valid @RequestBody request: OtpCodeRequest): RecoveryCodesView =
        twoFactor.confirmEnrollment(request.code)

    @PostMapping("/disable")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun disable(@Valid @RequestBody request: PasswordRequest) = twoFactor.disable(request.password)

    @PostMapping("/recovery-codes")
    fun regenerate(@Valid @RequestBody request: PasswordRequest): RecoveryCodesView =
        twoFactor.regenerateRecoveryCodes(request.password)
}

data class OtpCodeRequest(
    @field:NotBlank val code: String,
)

data class PasswordRequest(
    @field:NotBlank val password: String,
)

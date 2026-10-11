package com.duluin.ftth.iam.adapter.inbound.web

import com.duluin.ftth.iam.application.port.inbound.AccountQuery
import com.duluin.ftth.iam.application.service.AccountPasswordService
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/me")
@Tag(name = "Account")
@SecurityRequirement(name = "bearer-jwt")
class MeController(
    private val accountQuery: AccountQuery,
    private val passwords: AccountPasswordService,
) {
    @GetMapping
    fun me(): ProfileResponse = ProfileResponse.from(accountQuery.currentProfile())

    @PostMapping("/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun changePassword(@Valid @RequestBody request: ChangeAccountPasswordRequest) =
        passwords.changeOwn(request.currentPassword, request.newPassword)
}

data class ChangeAccountPasswordRequest(
    @field:NotBlank @field:Size(max = 72) val currentPassword: String,
    @field:NotBlank @field:Size(min = 8, max = 72) val newPassword: String,
)

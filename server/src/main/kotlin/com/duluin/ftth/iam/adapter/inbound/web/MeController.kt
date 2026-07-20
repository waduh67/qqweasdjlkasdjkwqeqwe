package com.duluin.ftth.iam.adapter.inbound.web

import com.duluin.ftth.iam.application.port.inbound.AccountQuery
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/me")
@Tag(name = "Account")
@SecurityRequirement(name = "bearer-jwt")
class MeController(
    private val accountQuery: AccountQuery,
) {
    @GetMapping
    fun me(): ProfileResponse = ProfileResponse.from(accountQuery.currentProfile())
}

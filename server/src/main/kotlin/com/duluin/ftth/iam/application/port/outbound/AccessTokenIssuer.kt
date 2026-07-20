package com.duluin.ftth.iam.application.port.outbound

import com.duluin.ftth.iam.domain.model.User
import java.time.Instant

/** Port penerbit access-token (JWT). Implementasi memakai secret HMAC di adapter. */
interface AccessTokenIssuer {

    fun issue(user: User, permissionCodes: Set<String>): IssuedToken
}

data class IssuedToken(
    val value: String,
    val expiresAt: Instant,
)

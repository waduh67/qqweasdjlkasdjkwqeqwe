package com.duluin.ftth.iam.application.port.outbound

import com.duluin.ftth.iam.domain.model.RefreshToken
import java.util.UUID

interface RefreshTokenRepository {

    fun save(token: RefreshToken): RefreshToken

    fun findTenantByTokenHash(tokenHash: String): UUID?

    fun consumeActive(tokenHash: String): RefreshToken?

    fun revokeByTokenHash(tokenHash: String)

    fun revokeAllForUser(userId: UUID)
}

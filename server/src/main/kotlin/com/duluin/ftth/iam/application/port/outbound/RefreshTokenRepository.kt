package com.duluin.ftth.iam.application.port.outbound

import com.duluin.ftth.iam.domain.model.RefreshToken
import java.util.UUID

interface RefreshTokenRepository {

    fun save(token: RefreshToken): RefreshToken

    fun findByTokenHash(tokenHash: String): RefreshToken?

    fun revokeAllForUser(userId: UUID)
}

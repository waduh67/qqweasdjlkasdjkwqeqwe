package com.duluin.ftth.common.infrastructure.security

import com.duluin.ftth.common.security.AuthenticatedUser
import com.duluin.ftth.common.security.JwtClaims
import org.springframework.core.convert.converter.Converter
import org.springframework.security.authentication.AbstractAuthenticationToken
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Mengubah [Jwt] yang sudah tervalidasi menjadi [FtthAuthenticationToken] dengan
 * principal [AuthenticatedUser] — membaca izin & area dari klaim, bukan dari DB,
 * sehingga request tetap stateless (staleness dibatasi TTL access-token).
 */
@Component
class JwtAuthenticationConverter : Converter<Jwt, AbstractAuthenticationToken> {

    override fun convert(jwt: Jwt): AbstractAuthenticationToken {
        val user = AuthenticatedUser(
            userId = UUID.fromString(jwt.subject),
            tenantId = UUID.fromString(jwt.requiredClaim(JwtClaims.TENANT_ID)),
            email = jwt.requiredClaim(JwtClaims.EMAIL),
            name = jwt.requiredClaim(JwtClaims.NAME),
            platformAdmin = jwt.getClaimAsBoolean(JwtClaims.PLATFORM_ADMIN) ?: false,
            permissions = jwt.getClaimAsStringList(JwtClaims.PERMISSIONS)?.toSet() ?: emptySet(),
            areaIds = jwt.getClaimAsStringList(JwtClaims.AREAS)?.map(UUID::fromString)?.toSet() ?: emptySet(),
        )
        return FtthAuthenticationToken(user)
    }

    private fun Jwt.requiredClaim(name: String): String =
        getClaimAsString(name) ?: throw IllegalArgumentException("Klaim '$name' wajib ada di token")
}

package com.duluin.ftth.iam.adapter.outbound.security

import com.duluin.ftth.common.infrastructure.config.SecurityProperties
import com.duluin.ftth.common.security.JwtClaims
import com.duluin.ftth.iam.application.port.outbound.AccessTokenIssuer
import com.duluin.ftth.iam.application.port.outbound.IssuedToken
import com.duluin.ftth.iam.domain.model.User
import org.springframework.security.oauth2.jose.jws.MacAlgorithm
import org.springframework.security.oauth2.jwt.JwsHeader
import org.springframework.security.oauth2.jwt.JwtClaimsSet
import org.springframework.security.oauth2.jwt.JwtEncoder
import org.springframework.security.oauth2.jwt.JwtEncoderParameters
import org.springframework.stereotype.Component
import java.time.Instant

/**
 * Menerbitkan access-token JWT (HS256) berisi identitas, izin efektif, dan area
 * (dimensi scope). Konsumen (security config) memverifikasinya secara stateless.
 */
@Component
class JwtAccessTokenIssuer(
    private val jwtEncoder: JwtEncoder,
    private val securityProperties: SecurityProperties,
) : AccessTokenIssuer {

    override fun issue(user: User, permissionCodes: Set<String>): IssuedToken {
        val issuedAt = Instant.now()
        val expiresAt = issuedAt.plus(securityProperties.accessTokenTtl)

        val claims = JwtClaimsSet.builder()
            .subject(user.id.toString())
            .issuedAt(issuedAt)
            .expiresAt(expiresAt)
            .claim(JwtClaims.TENANT_ID, user.tenantId.toString())
            .claim(JwtClaims.EMAIL, user.email.value)
            .claim(JwtClaims.NAME, user.name)
            .claim(JwtClaims.PLATFORM_ADMIN, user.platformAdmin)
            .claim(JwtClaims.PERMISSIONS, permissionCodes.sorted())
            .claim(JwtClaims.AREAS, user.areaIds.map { it.toString() })
            .build()

        val header = JwsHeader.with(MacAlgorithm.HS256).build()
        val token = jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).tokenValue
        return IssuedToken(value = token, expiresAt = expiresAt)
    }
}

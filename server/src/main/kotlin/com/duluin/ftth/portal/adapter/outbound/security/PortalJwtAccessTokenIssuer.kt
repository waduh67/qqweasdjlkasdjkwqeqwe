package com.duluin.ftth.portal.adapter.outbound.security

import com.duluin.ftth.common.infrastructure.config.SecurityProperties
import com.duluin.ftth.portal.application.port.outbound.PortalAccessTokenIssuer
import com.duluin.ftth.portal.application.port.outbound.PortalIssuedToken
import com.duluin.ftth.portal.security.PortalJwtClaims
import com.nimbusds.jose.jwk.source.ImmutableSecret
import com.nimbusds.jose.proc.SecurityContext
import org.springframework.security.oauth2.jose.jws.MacAlgorithm
import org.springframework.security.oauth2.jwt.JwsHeader
import org.springframework.security.oauth2.jwt.JwtClaimsSet
import org.springframework.security.oauth2.jwt.JwtEncoderParameters
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID
import javax.crypto.spec.SecretKeySpec

/**
 * Menerbitkan access-token JWT (HS256) untuk realm PORTAL, ditandatangani dengan secret
 * TERPISAH dari operator ([SecurityProperties.effectivePortalJwtSecret]) — token operator
 * dan portal tak bisa saling divalidasi. Encoder dibangun lokal (bukan bean) agar tak
 * bentrok dengan `jwtEncoder` operator.
 */
@Component
class PortalJwtAccessTokenIssuer(
    private val securityProperties: SecurityProperties,
) : PortalAccessTokenIssuer {

    private val encoder = NimbusJwtEncoder(
        ImmutableSecret<SecurityContext>(
            SecretKeySpec(securityProperties.effectivePortalJwtSecret.toByteArray(), "HmacSHA256"),
        ),
    )

    override fun issue(customerId: UUID, tenantId: UUID, login: String, name: String): PortalIssuedToken {
        val issuedAt = Instant.now()
        val expiresAt = issuedAt.plus(securityProperties.accessTokenTtl)

        val claims = JwtClaimsSet.builder()
            .subject(customerId.toString())
            .issuedAt(issuedAt)
            .expiresAt(expiresAt)
            .claim(PortalJwtClaims.TENANT_ID, tenantId.toString())
            .claim(PortalJwtClaims.LOGIN, login)
            .claim(PortalJwtClaims.NAME, name)
            .claim(PortalJwtClaims.TOKEN_USE, PortalJwtClaims.TOKEN_USE_PORTAL)
            .build()

        val header = JwsHeader.with(MacAlgorithm.HS256).build()
        val token = encoder.encode(JwtEncoderParameters.from(header, claims)).tokenValue
        return PortalIssuedToken(value = token, expiresAt = expiresAt)
    }
}

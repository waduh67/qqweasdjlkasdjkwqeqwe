package com.duluin.ftth.common.infrastructure.security

import com.duluin.ftth.common.security.CredentialSessionVerifier
import com.duluin.ftth.common.security.JwtClaims
import com.duluin.ftth.common.security.SessionIdentity
import org.springframework.security.oauth2.jwt.BadJwtException
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.jwt.JwtDecoder
import java.math.BigDecimal
import java.util.UUID

class CredentialJwtDecoder(private val signed: JwtDecoder, private val sessions: CredentialSessionVerifier) : JwtDecoder {
    override fun decode(token: String): Jwt {
        val jwt = signed.decode(token)
        val identity = try {
            SessionIdentity(UUID.fromString(requireNotNull(jwt.getClaimAsString(JwtClaims.TENANT_ID))),
                UUID.fromString(requireNotNull(jwt.subject)), jwt.id, jwt.credentialVersion())
        } catch (invalid: IllegalArgumentException) {
            throw BadJwtException("Invalid session identity", invalid)
        }
        if (!sessions.isCurrent(identity)) throw BadJwtException("Session credentials are no longer valid")
        return jwt
    }
}

internal fun Jwt.credentialVersion(): Long {
    if (!claims.containsKey(JwtClaims.CREDENTIAL_VERSION)) return 0
    return parseCredentialVersion(claims[JwtClaims.CREDENTIAL_VERSION])
}

internal fun parseCredentialVersion(claim: Any?): Long {
    if (claim !is Number) throw BadJwtException("Invalid credential version")
    val version = try { BigDecimal(claim.toString()).longValueExact() }
    catch (invalid: ArithmeticException) { throw BadJwtException("Invalid credential version", invalid) }
    catch (invalid: NumberFormatException) { throw BadJwtException("Invalid credential version", invalid) }
    if (version < 0) throw BadJwtException("Invalid credential version")
    return version
}

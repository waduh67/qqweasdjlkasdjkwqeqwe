package com.duluin.ftth.common.infrastructure.security

import com.duluin.ftth.common.security.CredentialSessionVerifier
import com.duluin.ftth.common.security.JwtClaims
import com.duluin.ftth.common.security.SessionIdentity
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.security.oauth2.jwt.BadJwtException
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.jwt.JwtDecoder
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

class CredentialJwtDecoderTest {
    private val tenant = UUID.randomUUID()
    private val user = UUID.randomUUID()
    private fun jwt(extra: Map<String, Any> = emptyMap()) = Jwt("signed", Instant.now(), Instant.now().plusSeconds(60),
        mapOf("alg" to "HS256"), mapOf("sub" to user.toString(), JwtClaims.TENANT_ID to tenant.toString()) + extra)

    @Test fun `version is exact and absent legacy claim is zero`() {
        assertThat(jwt().credentialVersion()).isZero()
        assertThat(jwt(mapOf(JwtClaims.CREDENTIAL_VERSION to BigDecimal("42.000"))).credentialVersion()).isEqualTo(42)
        assertThat(jwt(mapOf(JwtClaims.CREDENTIAL_VERSION to Long.MAX_VALUE)).credentialVersion()).isEqualTo(Long.MAX_VALUE)
    }

    @Test fun `null textual fractional negative oversized and nonfinite versions are rejected`() {
        assertThatThrownBy { parseCredentialVersion(null) }.isInstanceOf(BadJwtException::class.java)
        listOf("0", true, -1, BigDecimal("0.1"), BigDecimal("9223372036854775808"), Double.NaN, Double.POSITIVE_INFINITY).forEach { invalid ->
            assertThatThrownBy { jwt(mapOf(JwtClaims.CREDENTIAL_VERSION to invalid)).credentialVersion() }.isInstanceOf(BadJwtException::class.java)
        }
    }

    @Test fun `signature decoding precedes scoped credential verification`() {
        var received: SessionIdentity? = null
        val sessions = object : CredentialSessionVerifier {
            override fun isCurrent(identity: SessionIdentity): Boolean { received = identity; return true }
        }
        val decoded = jwt(mapOf(JwtClaims.CREDENTIAL_VERSION to 7))
        assertThat(CredentialJwtDecoder(JwtDecoder { decoded }, sessions).decode("token")).isSameAs(decoded)
        assertThat(received).isEqualTo(SessionIdentity(tenant, user, null, 7))
        received = null
        assertThatThrownBy { CredentialJwtDecoder(JwtDecoder { throw BadJwtException("Bad signature") }, sessions).decode("token") }
            .isInstanceOf(BadJwtException::class.java)
        assertThat(received).isNull()
    }

    @Test fun `stale credentials and invalid tenant identity are rejected`() {
        val sessions = object : CredentialSessionVerifier { override fun isCurrent(identity: SessionIdentity) = false }
        assertThatThrownBy { CredentialJwtDecoder(JwtDecoder { jwt() }, sessions).decode("token") }.isInstanceOf(BadJwtException::class.java)
        assertThatThrownBy { CredentialJwtDecoder(JwtDecoder { jwt(mapOf(JwtClaims.TENANT_ID to "invalid")) }, sessions).decode("token") }
            .isInstanceOf(BadJwtException::class.java)
    }
}

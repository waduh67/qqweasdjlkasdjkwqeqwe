package com.duluin.ftth.portal.adapter.inbound.security

import com.duluin.ftth.portal.security.PortalCustomer
import com.duluin.ftth.portal.security.PortalJwtClaims
import org.springframework.core.convert.converter.Converter
import org.springframework.security.authentication.AbstractAuthenticationToken
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Mengubah [Jwt] portal yang tervalidasi menjadi [PortalAuthenticationToken] dengan
 * principal [PortalCustomer]. Menolak token yang bukan `use=portal` — lapisan sabuk-dan-
 * bretel di atas isolasi secret: token dengan bentuk klaim lain tak akan lolos.
 */
@Component
class PortalJwtAuthenticationConverter : Converter<Jwt, AbstractAuthenticationToken> {

    override fun convert(jwt: Jwt): AbstractAuthenticationToken {
        require(jwt.getClaimAsString(PortalJwtClaims.TOKEN_USE) == PortalJwtClaims.TOKEN_USE_PORTAL) {
            "Token bukan token portal"
        }
        val customer = PortalCustomer(
            customerId = UUID.fromString(jwt.subject),
            tenantId = UUID.fromString(jwt.requiredClaim(PortalJwtClaims.TENANT_ID)),
            login = jwt.requiredClaim(PortalJwtClaims.LOGIN),
            name = jwt.requiredClaim(PortalJwtClaims.NAME),
        )
        return PortalAuthenticationToken(customer)
    }

    private fun Jwt.requiredClaim(name: String): String =
        getClaimAsString(name) ?: throw IllegalArgumentException("Klaim '$name' wajib ada di token portal")
}

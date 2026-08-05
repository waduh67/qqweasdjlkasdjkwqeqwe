package com.duluin.ftth.portal.adapter.inbound.security

import com.duluin.ftth.common.infrastructure.config.SecurityProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.HttpStatus
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.oauth2.jose.jws.MacAlgorithm
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import org.springframework.security.oauth2.server.resource.web.access.BearerTokenAccessDeniedHandler
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.HttpStatusEntryPoint
import javax.crypto.spec.SecretKeySpec

/**
 * Rantai keamanan tersendiri untuk realm PORTAL pelanggan (path berawalan `/api/portal/`).
 *
 * Dipisah dari rantai operator karena identitasnya beda jenis: [com.duluin.ftth.portal.security.PortalCustomer]
 * (pelanggan, tanpa RBAC), bukan pengguna IAM. Token-nya ditandatangani secret TERPISAH
 * ([SecurityProperties.effectivePortalJwtSecret]) sehingga token operator gagal di decoder
 * ini dan sebaliknya — dua realm tak bisa saling bocor. Pola sama `CollectorSecurityConfig`.
 *
 * [Order] lebih tinggi dari rantai utama (yang mencocokkan semua request) agar rantai
 * spesifik ini dievaluasi lebih dulu; kalau tidak, Spring Security menolak konfigurasi
 * saat startup karena rantai ini tak akan pernah terpanggil.
 */
@Configuration
class PortalSecurityConfig(
    private val securityProperties: SecurityProperties,
) {

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE + 10)
    fun portalSecurityFilterChain(
        http: HttpSecurity,
        portalJwtAuthenticationConverter: PortalJwtAuthenticationConverter,
    ): SecurityFilterChain {
        http
            .securityMatcher("/api/portal/**")
            .csrf { it.disable() }
            .cors { }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .authorizeHttpRequests {
                // Masuk & refresh terbuka (kredensial diverifikasi di dalam); sisanya wajib token portal.
                it.requestMatchers("/api/portal/auth/login", "/api/portal/auth/refresh").permitAll()
                it.anyRequest().authenticated()
            }
            .oauth2ResourceServer { oauth2 ->
                oauth2.jwt { jwt ->
                    jwt.decoder(portalJwtDecoder())
                    jwt.jwtAuthenticationConverter(portalJwtAuthenticationConverter)
                }
                oauth2.authenticationEntryPoint(HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED))
                oauth2.accessDeniedHandler(BearerTokenAccessDeniedHandler())
            }
            .addFilterAfter(PortalTenantContextFilter(), BearerTokenAuthenticationFilter::class.java)
            .anonymous(AbstractHttpConfigurer<*, *>::disable)
        return http.build()
    }

    /**
     * Decoder token portal — dibangun lokal (bukan bean) memakai secret portal, supaya tak
     * bentrok dengan bean `jwtDecoder` operator dan realm tetap terisolasi.
     */
    private fun portalJwtDecoder(): JwtDecoder {
        val key = SecretKeySpec(securityProperties.effectivePortalJwtSecret.toByteArray(), "HmacSHA256")
        return NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build()
    }
}

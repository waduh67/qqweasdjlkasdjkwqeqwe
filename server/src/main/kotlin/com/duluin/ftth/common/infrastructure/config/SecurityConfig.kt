package com.duluin.ftth.common.infrastructure.config

import com.duluin.ftth.common.infrastructure.security.JwtAuthenticationConverter
import com.duluin.ftth.common.infrastructure.security.TenantContextFilter
import jakarta.servlet.http.HttpServletRequest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.annotation.Order
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.security.authorization.AuthorizationDecision
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.security.crypto.password.PasswordEncoder
import com.nimbusds.jose.jwk.source.ImmutableSecret
import com.nimbusds.jose.proc.SecurityContext
import org.springframework.security.oauth2.jose.jws.MacAlgorithm
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.JwtEncoder
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder
import org.springframework.security.oauth2.server.resource.web.access.BearerTokenAccessDeniedHandler
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.HttpStatusEntryPoint
import org.springframework.web.cors.CorsConfiguration
import org.springframework.web.cors.CorsConfigurationSource
import org.springframework.web.cors.UrlBasedCorsConfigurationSource
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import javax.crypto.spec.SecretKeySpec

@Configuration
@EnableMethodSecurity
class SecurityConfig {

    @Bean
    fun passwordEncoder(): PasswordEncoder = BCryptPasswordEncoder()

    @Bean
    fun jwtDecoder(securityProperties: SecurityProperties): JwtDecoder {
        val key = SecretKeySpec(securityProperties.jwtSecret.toByteArray(), "HmacSHA256")
        return NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build()
    }

    @Bean
    fun jwtEncoder(securityProperties: SecurityProperties): JwtEncoder {
        val key = SecretKeySpec(securityProperties.jwtSecret.toByteArray(), "HmacSHA256")
        return NimbusJwtEncoder(ImmutableSecret<SecurityContext>(key))
    }

    @Bean
    fun corsConfigurationSource(corsProperties: CorsProperties): CorsConfigurationSource {
        val config = CorsConfiguration().apply {
            allowedOrigins = corsProperties.allowedOrigins
            allowedMethods = listOf("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
            allowedHeaders = listOf("*")
            allowCredentials = true
        }
        return UrlBasedCorsConfigurationSource().apply { registerCorsConfiguration("/**", config) }
    }

    /**
     * Chain khusus `/actuator/prometheus`, dipasang SEBELUM chain utama.
     *
     * Yang menjemput metrik adalah Prometheus, bukan manusia: ia tak bisa masuk, tak bisa
     * menyegarkan token, dan hidup selama bertahun-tahun. Jadi bearer JWT kita yang berumur
     * 15 menit sama sekali tak cocok, sementara membuka endpoint ini tanpa syarat berarti
     * membagikan bentuk sistem, nama job, dan volume kerja tiap tenant kepada siapa saja
     * yang menebak URL-nya. Jalan tengahnya token statis panjang, dibandingkan dengan
     * waktu tetap, dan MATI secara bawaan — belum disetel berarti tertutup, bukan terbuka.
     */
    @Bean
    @Order(1)
    fun metricsFilterChain(http: HttpSecurity, observability: ObservabilityProperties): SecurityFilterChain {
        val expected = observability.metricsToken
        http
            .securityMatcher("/actuator/prometheus")
            .csrf { it.disable() }
            .cors { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .authorizeHttpRequests { registry ->
                registry.anyRequest().access { _, context ->
                    AuthorizationDecision(matchesMetricsToken(context.request, expected))
                }
            }
            .httpBasic { it.disable() }
            .formLogin { it.disable() }
        return http.build()
    }

    @Bean
    fun filterChain(
        http: HttpSecurity,
        jwtDecoder: JwtDecoder,
        jwtAuthenticationConverter: JwtAuthenticationConverter,
    ): SecurityFilterChain {
        http
            .csrf { it.disable() }
            .cors { }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .authorizeHttpRequests {
                it.requestMatchers(
                    "/api/auth/login",
                    "/api/auth/refresh",
                    // Pendaftaran mandiri ISP: publik (bikin tenant + admin awal). Keunikan
                    // slug/email dijaga di service, bukan lewat auth.
                    "/api/signup",
                    // Callback Pivot: satu URL per produk di akun MASTER, Pivot memanggil dengan
                    // `X-API-Key` master-nya sendiri (diverifikasi di lapis billing), bukan bearer.
                    "/api/platform/pivot/callbacks/**",
                    // Provisioning VPN: VPS mengunduh installer & memanggil balik (verify
                    // user/pass, minta IP overlay) dengan token node-nya sendiri, bukan bearer.
                    "/api/vpn/provision/**",
                    // Halaman bayar publik: tautan `/bayar/<slug>/<uuid>` dikirim ke pelanggan
                    // lewat WhatsApp, jadi kapabilitasnya UUID tagihan di path — bukan bearer.
                    "/api/public/**",
                    "/api/hotspot/public/**",
                    "/actuator/health/**",
                    "/swagger-ui/**",
                    "/swagger-ui.html",
                    "/v3/api-docs/**",
                ).permitAll()
                it.anyRequest().authenticated()
            }
            .oauth2ResourceServer { oauth2 ->
                oauth2.jwt { jwt ->
                    jwt.decoder(jwtDecoder)
                    jwt.jwtAuthenticationConverter(jwtAuthenticationConverter)
                }
                oauth2.authenticationEntryPoint(HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED))
                oauth2.accessDeniedHandler(BearerTokenAccessDeniedHandler())
            }
            // Pasang tenant ke context SETELAH autentikasi bearer token selesai.
            .addFilterAfter(TenantContextFilter(), BearerTokenAuthenticationFilter::class.java)
            .anonymous(AbstractHttpConfigurer<*, *>::disable)
        return http.build()
    }

    private companion object {
        /**
         * Token kosong = tertutup. Perbandingan lewat [MessageDigest.isEqual] yang berwaktu
         * tetap, supaya lamanya jawaban tak membocorkan berapa karakter awal yang benar.
         */
        fun matchesMetricsToken(request: HttpServletRequest, expected: String): Boolean {
            if (expected.isBlank()) return false
            val presented = request.getHeader("X-Metrics-Token")
                ?: request.getHeader(HttpHeaders.AUTHORIZATION)?.removePrefix("Bearer ")?.trim()
                ?: return false
            return MessageDigest.isEqual(
                presented.toByteArray(StandardCharsets.UTF_8),
                expected.toByteArray(StandardCharsets.UTF_8),
            )
        }
    }
}

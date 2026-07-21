package com.duluin.ftth.monitoring.adapter.inbound.security

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.HttpMethod
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter

/**
 * Rantai keamanan tersendiri untuk endpoint collector.
 *
 * Dipisah dari rantai pengguna karena model autentikasinya berbeda jenis: API key
 * mesin, bukan JWT pengguna. Menyatukannya berarti setiap filter JWT harus tahu
 * cara melewatkan collector — dan setiap filter collector harus tahu cara
 * melewatkan pengguna. Dipisah, keduanya tetap sederhana dan tidak bisa saling
 * bocor.
 *
 * [Ordered.HIGHEST_PRECEDENCE] agar dievaluasi sebelum rantai utama; pencocokan
 * dibatasi ketat ke jalur di bawah `/api/collector` saja.
 *
 * (Pola jalur sengaja tidak ditulis lengkap di komentar ini: komentar blok Kotlin
 * bisa bersarang, sehingga tanda bintang ganda di dalamnya membuka komentar baru
 * yang tidak pernah tertutup.)
 */
@Configuration
class CollectorSecurityConfig(
    private val authenticator: CollectorAuthenticator,
) {
    /**
     * Filter dibuat di sini, bukan disuntik sebagai bean: bean bertipe `Filter`
     * otomatis didaftarkan Spring Boot ke rantai servlet global dan akan
     * berjalan untuk seluruh request aplikasi, bukan hanya jalur collector.
     */
    private val collectorApiKeyFilter = CollectorApiKeyFilter(authenticator)
    /**
     * `@Order` HARUS berada di method bean-nya, bukan di kelas konfigurasi:
     * Spring Security mengurutkan `SecurityFilterChain` berdasarkan urutan
     * bean-nya. Salah menaruhnya membuat rantai utama (yang cocok dengan segala
     * request) terdaftar lebih dulu sehingga rantai ini tidak akan pernah
     * terpanggil — dan Spring Security menolak konfigurasi itu saat startup.
     */
    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    fun collectorSecurityFilterChain(http: HttpSecurity): SecurityFilterChain {
        http
            .securityMatcher("/api/collector/**")
            // Collector adalah klien non-browser dengan kredensial di header, bukan
            // cookie, sehingga tidak ada permukaan serangan CSRF.
            .csrf { it.disable() }
            .cors { }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .anonymous { it.disable() }
            .authorizeHttpRequests {
                // Collector hanya menulis; tidak ada endpointnya yang membaca data
                // pelanggan, jadi kunci bocor pun tidak membuka data tenant.
                it.requestMatchers(HttpMethod.POST, "/api/collector/**").authenticated()
                it.anyRequest().denyAll()
            }
            .addFilterBefore(collectorApiKeyFilter, UsernamePasswordAuthenticationFilter::class.java)

        return http.build()
    }
}

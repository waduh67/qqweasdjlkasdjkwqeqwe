package com.duluin.ftth.cpe.adapter.outbound.acs

import java.time.Duration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.web.client.RestClient

/**
 * Merakit [RestClient] khusus GenieACS NBI. Dinonaktifkan di profil `test`: di sana
 * [com.duluin.ftth.cpe.application.port.outbound.AcsGateway] dipenuhi test double
 * in-memory, sehingga uji tak menuntut GenieACS hidup.
 */
@Configuration
@Profile("!test")
class GenieAcsConfig {

    @Bean
    fun genieAcsRestClient(properties: GenieAcsProperties): RestClient =
        build(properties, properties.readTimeout)

    /**
     * Klien KEDUA, hanya untuk probe kesehatan — bedanya cuma `readTimeout` yang jauh lebih
     * pendek. Memakai ulang klien utama berarti kartu "Health Check" menggantung halaman
     * selama `readTimeout` (15 detik) tiap kali ACS mati; padahal justru saat itulah
     * operator paling butuh halamannya cepat merender.
     */
    @Bean
    fun genieAcsHealthRestClient(properties: GenieAcsProperties): RestClient =
        build(properties, properties.healthTimeout)

    private fun build(properties: GenieAcsProperties, readTimeout: Duration): RestClient {
        val requestFactory = SimpleClientHttpRequestFactory().apply {
            setConnectTimeout(properties.connectTimeout)
            setReadTimeout(readTimeout)
        }
        return RestClient.builder()
            .baseUrl(properties.baseUrl)
            .requestFactory(requestFactory)
            .apply {
                if (properties.username.isNotBlank()) {
                    it.defaultHeaders { headers -> headers.setBasicAuth(properties.username, properties.password) }
                }
            }
            .build()
    }
}

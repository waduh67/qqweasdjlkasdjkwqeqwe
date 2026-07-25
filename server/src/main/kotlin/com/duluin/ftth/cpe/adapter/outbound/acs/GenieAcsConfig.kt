package com.duluin.ftth.cpe.adapter.outbound.acs

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
    fun genieAcsRestClient(properties: GenieAcsProperties): RestClient {
        val requestFactory = SimpleClientHttpRequestFactory().apply {
            setConnectTimeout(properties.connectTimeout)
            setReadTimeout(properties.readTimeout)
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

package com.duluin.ftth.cpe.adapter.outbound.acs

import com.duluin.ftth.cpe.application.port.outbound.AcsConnectionProbe
import com.duluin.ftth.cpe.application.port.outbound.AcsProbe
import com.duluin.ftth.cpe.application.port.outbound.AcsSettingsResolver
import com.duluin.ftth.cpe.domain.model.AcsConnectionSettings
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.client.HttpClientErrorException
import java.time.Duration
import java.util.UUID

class AcsClientSnapshot(val version: UUID, val client: RestClient, val healthClient: RestClient)

@Component
class GenieAcsClientProvider(private val settings: AcsSettingsResolver, private val properties: GenieAcsProperties) : AcsConnectionProbe {
    @Volatile private var cached: AcsClientSnapshot? = null

    fun snapshot(): AcsClientSnapshot = snapshot(settings.current())

    @Synchronized
    private fun snapshot(config: AcsConnectionSettings): AcsClientSnapshot {
        cached?.takeIf { it.version == config.version }?.let { return it }
        return AcsClientSnapshot(config.version, build(config, properties.readTimeout), build(config, properties.healthTimeout)).also { cached = it }
    }

    override fun test(settings: AcsConnectionSettings): AcsProbe {
        val started = System.nanoTime()
        return try {
            snapshot(settings).healthClient.get()
                .uri { it.path("/devices/").queryParam("projection", "_id").queryParam("limit", 1).build() }
                .retrieve().toBodilessEntity()
            AcsProbe(true, (System.nanoTime() - started) / 1_000_000, null)
        } catch (failure: Exception) {
            val error = when (failure) {
                is HttpClientErrorException -> if (failure.statusCode.value() in setOf(401, 403)) "Autentikasi API ditolak" else "HTTP "+failure.statusCode.value()
                else -> failure.javaClass.simpleName
            }
            AcsProbe(false, null, error)
        }
    }

    private fun build(config: AcsConnectionSettings, timeout: Duration): RestClient = RestClient.builder()
        .baseUrl(config.nbiUrl)
        .requestFactory(SimpleClientHttpRequestFactory().apply {
            setConnectTimeout(properties.connectTimeout)
            setReadTimeout(timeout)
        })
        .apply { builder -> if (config.username.isNotBlank()) builder.defaultHeaders { it.setBasicAuth(config.username, config.password.orEmpty()) } }
        .build()
}

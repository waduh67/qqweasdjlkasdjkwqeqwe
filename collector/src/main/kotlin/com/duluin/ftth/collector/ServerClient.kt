package com.duluin.ftth.collector

import com.duluin.ftth.contract.CollectorConfig
import com.duluin.ftth.contract.CollectorHeartbeat
import com.duluin.ftth.contract.CollectorProtocol
import com.duluin.ftth.contract.IngestResult
import com.duluin.ftth.contract.MetricBatch
import org.slf4j.LoggerFactory
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Klien HTTP ke ftth-server. Selalu outbound, sehingga tidak ada port yang perlu
 * dibuka di sisi ISP.
 *
 * Memakai HttpClient bawaan JDK: agent ini di-deploy di mesin milik operator dan
 * setiap dependensi tambahan adalah beban pemeliharaan bagi mereka, bukan bagi kita.
 */
class ServerClient(
    private val baseUrl: String,
    private val apiKey: String,
    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build(),
) {
    private val log = LoggerFactory.getLogger(javaClass)

    // Jackson 3 sudah membawa dukungan java.time di databind, jadi Instant pada
    // tipe kontrak tidak perlu modul tambahan.
    private val mapper = JsonMapper.builder()
        .addModule(KotlinModule.Builder().build())
        // Server boleh menambah field baru tanpa memaksa seluruh armada collector
        // ikut di-upgrade lebih dulu.
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .build()

    /** Melapor hidup dan mengambil konfigurasi polling terbaru. */
    fun heartbeat(heartbeat: CollectorHeartbeat): CollectorConfig =
        send("/api/collector/heartbeat", heartbeat, CollectorConfig::class.java)

    fun pushMetrics(batch: MetricBatch): IngestResult =
        send("/api/collector/metrics", batch, IngestResult::class.java)

    private fun <T> send(path: String, body: Any, responseType: Class<T>): T {
        val request = HttpRequest.newBuilder(URI.create("$baseUrl$path"))
            .timeout(Duration.ofSeconds(30))
            .header("Content-Type", "application/json")
            .header(CollectorProtocol.API_KEY_HEADER, apiKey)
            .header(CollectorProtocol.PROTOCOL_VERSION_HEADER, CollectorProtocol.PROTOCOL_VERSION.toString())
            .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
            .build()

        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) {
            throw ServerRejectedException(response.statusCode(), response.body())
        }
        log.debug("POST {} -> {}", path, response.statusCode())
        return mapper.readValue(response.body(), responseType)
    }
}

/**
 * Server menolak permintaan. Kode statusnya dibawa karena penanganannya berbeda:
 * 401 berarti API key salah dan mencoba lagi tidak akan menolong, sedangkan 5xx
 * layak diulang.
 */
class ServerRejectedException(val statusCode: Int, val body: String) :
    RuntimeException("Server menolak permintaan (HTTP $statusCode): ${body.take(300)}") {

    /** Kesalahan konfigurasi/otorisasi — mengulang tidak akan mengubah hasilnya. */
    val permanent: Boolean get() = statusCode in 400..499 && statusCode != 429
}

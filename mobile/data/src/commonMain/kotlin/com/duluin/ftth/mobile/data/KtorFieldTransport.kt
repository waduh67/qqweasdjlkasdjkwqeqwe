package com.duluin.ftth.mobile.data

import com.duluin.ftth.mobile.domain.FieldEvidence
import com.duluin.ftth.mobile.domain.FieldHttpResponse
import com.duluin.ftth.mobile.domain.FieldHttpTransport
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import com.duluin.ftth.mobile.data.MaterialJson.text
import com.duluin.ftth.mobile.data.MaterialJson.number

class KtorFieldTransport : FieldHttpTransport {
    private val client = HttpClient {
        expectSuccess = false
        followRedirects = false
        install(HttpTimeout) { requestTimeoutMillis = 30_000; connectTimeoutMillis = 15_000; socketTimeoutMillis = 30_000 }
    }
    override suspend fun send(server: String, path: String, token: String?, body: String?, key: String?, evidence: FieldEvidence?): FieldHttpResponse {
        require(path.startsWith("/api/") && !path.contains(".."))
        val response = client.request(normalizeServer(server) + path) {
            method = if (body == null) HttpMethod.Get else HttpMethod.Post
            headers.append(HttpHeaders.Accept, "application/json")
            token?.let { headers.append(HttpHeaders.Authorization, "Bearer $it") }
            key?.let { headers.append("Idempotency-Key", it) }
            if (evidence != null) {
                val fields = MaterialJson.parse(requireNotNull(body))
                setBody(MultiPartFormDataContent(formData {
                    append("expectedRevision", fields.number("expectedRevision").toString())
                    append("slot", fields.text("slot"))
                    append("file", evidence.bytes, Headers.build {
                        append(HttpHeaders.ContentType, evidence.contentType)
                        append(HttpHeaders.ContentDisposition, "filename=evidence")
                    })
                }))
            } else body?.let { contentType(ContentType.Application.Json); setBody(it) }
        }
        return FieldHttpResponse(response.status.value, response.bodyAsText())
    }
}

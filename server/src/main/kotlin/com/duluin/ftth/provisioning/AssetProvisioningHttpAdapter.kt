package com.duluin.ftth.provisioning

import com.duluin.ftth.fulfillment.AssetProvisioningOutcome
import com.duluin.ftth.fulfillment.AssetProvisioningPort
import com.duluin.ftth.fulfillment.AssetProvisioningWork
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import org.springframework.web.client.RestClientResponseException
import java.net.URI
import java.time.Duration
import java.util.UUID

@Component
class AssetProvisioningHttpAdapter(
    @Value("\${ftth.provisioning.asset-adapter-url:}") private val endpoint: String,
    @Value("\${ftth.provisioning.asset-adapter-token:}") private val token: String,
) : AssetProvisioningPort {
    private val client = RestClient.builder().requestFactory(SimpleClientHttpRequestFactory().apply {
        setConnectTimeout(Duration.ofSeconds(3)); setReadTimeout(Duration.ofSeconds(10))
    }).build()
    data class Acknowledgement(val operationId: UUID, val state: String)

    override fun apply(work: AssetProvisioningWork): AssetProvisioningOutcome {
        if (endpoint.isBlank()) return AssetProvisioningOutcome.ReconciliationRequired("ASSET_ADAPTER_NOT_CONFIGURED")
        require(URI(endpoint).scheme in setOf("http", "https"))
        return try {
            val request = client.post().uri(endpoint).header("Idempotency-Key", work.operationId.toString())
            if (token.isNotBlank()) request.header("Authorization", "Bearer $token")
            val reply = request.body(work).retrieve().body(Acknowledgement::class.java)
            if (reply?.operationId == work.operationId && reply.state == "SUCCEEDED") AssetProvisioningOutcome.Succeeded
            else AssetProvisioningOutcome.ReconciliationRequired("ASSET_ADAPTER_ACKNOWLEDGEMENT_INVALID")
        } catch (failure: RestClientResponseException) {
            AssetProvisioningOutcome.ReconciliationRequired("ASSET_ADAPTER_HTTP_${failure.statusCode.value()}")
        } catch (failure: RestClientException) {
            AssetProvisioningOutcome.ReconciliationRequired("ASSET_ADAPTER_TRANSPORT_FAILURE")
        }
    }
}

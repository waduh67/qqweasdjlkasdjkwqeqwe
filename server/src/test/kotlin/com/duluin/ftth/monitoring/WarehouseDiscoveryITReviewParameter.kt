package com.duluin.ftth.monitoring

import com.duluin.ftth.cpe.adapter.outbound.acs.GenieAcsGateway
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient
import java.time.Duration
import java.time.Instant

class WarehouseDiscoveryITReviewParameter {
    @Test
    fun `CPE-3 a fresh sibling cannot certify a future password timestamp`() {
        val builder = RestClient.builder().baseUrl("http://owned-acs.test")
        val server = MockRestServiceServer.bindTo(builder).build()
        val gateway = GenieAcsGateway(builder.build(), builder.build(), "", "http://owned.test/download", "http://owned.test/upload",
            1024, Duration.ofMillis(50), Duration.ofMillis(5))
        val now = Instant.now()
        server.expect(requestTo(containsString("/devices/"))).andRespond(withSuccess("""[{
            "_id":"OWNED","_deviceId":{"_SerialNumber":"SERIAL"},"_lastInform":"$now",
            "InternetGatewayDevice":{"LANDevice":{"1":{"WLANConfiguration":{"1":{
                "SSID":{"_value":"current","_timestamp":"$now"},
                "KeyPassphrase":{"_value":"untrusted-future","_timestamp":"${now.plusSeconds(86400)}"}
            }}}}}}]""", MediaType.APPLICATION_JSON))
        assertThat(gateway.wifiNetworks("OWNED").single().observedAt).isNull()
        server.verify()
    }
}

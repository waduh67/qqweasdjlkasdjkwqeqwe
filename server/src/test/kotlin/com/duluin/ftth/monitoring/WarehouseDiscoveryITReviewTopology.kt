package com.duluin.ftth.monitoring

import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.customer.CustomerObservationApi
import com.duluin.ftth.customer.ObservationPath
import com.duluin.ftth.network.NetworkApi
import com.jayway.jsonpath.JsonPath
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class WarehouseDiscoveryITReviewTopology : WarehouseDiscoveryFixture() {
    @MockitoSpyBean private lateinit var network: NetworkApi
    @Autowired private lateinit var observations: CustomerObservationApi
    private data class Path(val odp: UUID, val olt: UUID, val code: String)
    private fun path(token: String, name: String): Path {
        fun id(json: String): String = JsonPath.read(json, "$.id")
        val site = id(post("/api/sites", token, """{"code":"S-$name","name":"Site","location":{"longitude":106.99,"latitude":-6.24}}"""))
        val olt = id(post("/api/olts", token, """{"siteId":"$site","code":"O-$name","name":"OLT","vendor":"ZTE","managementIp":"127.0.0.1","snmpCommunity":"owned"}"""))
        val pon = id(post("/api/olts/$olt/pon-ports", token, """{"label":"1/1/1"}"""))
        val odc = id(post("/api/odcs", token, """{"code":"C-$name","name":"ODC","ponPortId":"$pon","splitterRatio":"1:8","capacity":8,"location":{"longitude":106.99,"latitude":-6.24}}"""))
        val odp = id(post("/api/odps", token, """{"code":"P-$name","name":"ODP","odcId":"$odc","splitterRatio":"1:8","capacity":8,"location":{"longitude":106.99,"latitude":-6.24}}"""))
        return Path(UUID.fromString(odp), UUID.fromString(olt), "O-$name")
    }

    @Test
    fun `topology attribution cannot backdate a delayed transaction before an already committed move`() {
        val token = newTenantAdmin("reviewtopology")
        val device = legacy(token)
        val first = path(token, "FIRST")
        val second = path(token, "SECOND")
        val third = path(token, "THIRD")
        fun attach(target: Path): Int = mockMvc.perform(post("/api/customers/onus/${device.onu}/attach")
            .header("Authorization", "Bearer $token").contentType(MediaType.APPLICATION_JSON)
            .content("""{"odpId":"${target.odp}","portNumber":1}""")).andReturn().response.status
        assertThat(attach(first)).isEqualTo(200)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        Mockito.doAnswer { invocation ->
            val value = invocation.callRealMethod()
            if (Thread.currentThread().name == "old-topology-transaction") {
                entered.countDown(); check(release.await(30, TimeUnit.SECONDS))
            }
            value
        }.`when`(network).requireOdp(third.odp)
        Executors.newSingleThreadExecutor { task -> Thread(task, "old-topology-transaction") }.use { executor ->
            val late = executor.submit<Int> { attach(third) }
            val observed: Instant
            try {
                check(entered.await(20, TimeUnit.SECONDS))
                assertThat(attach(second)).isEqualTo(200)
                observed = Instant.now()
            } finally { release.countDown() }
            assertThat(late.get(30, TimeUnit.SECONDS)).isEqualTo(200)
            val result = TenantContext.runAs(tenantId(token)) {
                observations.resolveObservation(device.serial, observed, ObservationPath(second.olt, second.code, "1/1/1"))
            }
            assertThat(result.reason).isNull()
            assertThat(result.episode?.onu?.id.toString()).isEqualTo(device.onu)
        }
    }
}

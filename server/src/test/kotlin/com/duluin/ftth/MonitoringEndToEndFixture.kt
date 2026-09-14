package com.duluin.ftth

import com.duluin.ftth.contract.CollectorProtocol
import com.duluin.ftth.iam.application.port.inbound.OnboardTenantCommand
import com.duluin.ftth.iam.application.port.inbound.OnboardTenantUseCase
import com.duluin.ftth.monitoring.application.port.outbound.CollectorRepository
import com.duluin.ftth.monitoring.application.service.SilentCollectorEvaluator
import com.jayway.jsonpath.JsonPath
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Instant
import java.util.UUID

abstract class MonitoringEndToEndFixture {
    @Autowired protected lateinit var mockMvc: MockMvc
    @Autowired private lateinit var onboarding: OnboardTenantUseCase
    @Autowired protected lateinit var silentCollectorEvaluator: SilentCollectorEvaluator
    @Autowired protected lateinit var collectorRepository: CollectorRepository
    private val pass = "secret12345"

    protected fun uniq() = UUID.randomUUID().toString().substring(0, 8)

    private fun login(slug: String, email: String): String {
        val json = mockMvc.perform(
            post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("""{"tenantSlug":"$slug","email":"$email","password":"$pass"}"""),
        ).andExpect(status().isOk).andReturn().response.contentAsString
        return JsonPath.read(json, "$.accessToken")
    }

    protected fun newTenantAdmin(prefix: String): String {
        val slug = "$prefix${uniq()}"
        val admin = "admin@$slug.test"
        onboarding.onboard(OnboardTenantCommand(slug, "Tenant $slug", admin, "Admin", pass))
        return login(slug, admin)
    }

    protected fun post(url: String, token: String, body: String): String =
        mockMvc.perform(post(url).header("Authorization", "Bearer $token")
            .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isCreated).andReturn().response.contentAsString

    protected fun postAsCollector(url: String, apiKey: String, body: String): String =
        mockMvc.perform(post(url).header(CollectorProtocol.API_KEY_HEADER, apiKey)
            .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isOk).andReturn().response.contentAsString

    protected fun newCollector(token: String): String {
        val json = post("/api/monitoring/collectors", token,
            """{"name":"Collector ${uniq()}","pollIntervalSeconds":60}""")
        return JsonPath.read(json, "$.apiKey")
    }

    private fun id(json: String): String = JsonPath.read(json, "$.id")

    protected fun registerOnu(token: String): String {
        val suffix = uniq().uppercase()
        val customer = id(post("/api/customers", token,
            """{"code":"C-$suffix","name":"Pelanggan $suffix","address":"Jl. Uji",
                "location":{"longitude":106.99,"latitude":-6.24}}"""))
        com.duluin.ftth.customer.LegacyOnuTestFixture.stage(customer, "SN-$suffix")
        return "SN-$suffix"
    }

    protected fun provisionAttachedOnu(token: String): Pair<String, String> {
        val suffix = uniq().uppercase()
        val site = id(post("/api/sites", token,
            """{"code":"POP-$suffix","name":"POP $suffix","location":{"longitude":106.98,"latitude":-6.23}}"""))
        val olt = id(post("/api/olts", token,
            """{"siteId":"$site","code":"OLT-$suffix","name":"OLT $suffix","vendor":"ZTE",
                "managementIp":"10.0.0.1","snmpCommunity":"rahasia"}"""))
        val pon = id(post("/api/olts/$olt/pon-ports", token, """{"label":"1/1/1"}"""))
        val odc = id(post("/api/odcs", token,
            """{"code":"ODC-$suffix","name":"ODC $suffix","location":{"longitude":106.99,"latitude":-6.24},
                "ponPortId":"$pon","splitterRatio":"1:8","capacity":64}"""))
        val odp = id(post("/api/odps", token,
            """{"code":"ODP-$suffix","name":"ODP $suffix","location":{"longitude":106.995,"latitude":-6.245},
                "odcId":"$odc","splitterRatio":"1:8","capacity":8}"""))
        val customerId = id(post("/api/customers", token,
            """{"code":"C-$suffix","name":"Pelanggan $suffix","address":"Jl. Uji",
                "location":{"longitude":106.99,"latitude":-6.24}}"""))
        val onuId = com.duluin.ftth.customer.LegacyOnuTestFixture.stage(customerId, "SN-$suffix")
        mockMvc.perform(post("/api/customers/onus/$onuId/attach").header("Authorization", "Bearer $token")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""{"odpId":"$odp","portNumber":1,"installRxPowerDbm":-22.0}"""))
            .andExpect(status().isOk)
        return customerId to "SN-$suffix"
    }

    protected fun reading(serial: String, status: String, rxPower: Double?): String = """
        {"serialNumber":"$serial","oltCode":"OLT-X","ponPortLabel":"1/1/1","status":"$status",
         "rxPowerDbm":${rxPower ?: "null"},"txPowerDbm":null,"uptimeSeconds":null,
         "distanceMeters":null,"observedAt":"${Instant.now()}"}
    """.trimIndent()

    protected fun batch(vararg readings: String, batchId: String = uniq()): String =
        """{"batchId":"$batchId","collectedAt":"${Instant.now()}","readings":[${readings.joinToString(",") }]}"""
}

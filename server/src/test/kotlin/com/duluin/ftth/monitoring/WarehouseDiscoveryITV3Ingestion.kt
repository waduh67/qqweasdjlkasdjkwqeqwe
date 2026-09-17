package com.duluin.ftth.monitoring

import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.contract.CollectorProtocol
import com.duluin.ftth.contract.MetricBatch
import com.duluin.ftth.contract.OltTarget
import com.duluin.ftth.contract.OnuPathProvenance
import com.duluin.ftth.contract.OnuReading
import com.duluin.ftth.customer.LegacyOnuTestFixture
import com.duluin.ftth.monitoring.application.service.OltReadingPersister
import com.duluin.ftth.monitoring.application.service.ServerSideOltPoller
import com.duluin.ftth.network.NetworkApi
import com.duluin.ftth.snmp.AdapterRegistry
import com.duluin.ftth.snmp.GponSnmpAdapter
import com.duluin.ftth.snmp.MibProfiles
import com.duluin.ftth.snmp.MibProfile
import com.duluin.ftth.snmp.SnmpReader
import com.duluin.ftth.snmp.SnmpReaderFactory
import com.jayway.jsonpath.JsonPath
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.provider.ValueSource
import org.junit.jupiter.params.provider.CsvSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.MediaType
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.web.client.RestClient
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ObjectNode
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.time.Instant
import java.util.UUID

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = ["server.address=127.0.0.1"])
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class WarehouseDiscoveryITV3Ingestion : WarehouseDiscoveryFixture() {
    @LocalServerPort private var port: Int = 0
    @Autowired private lateinit var network: NetworkApi
    @Autowired private lateinit var persister: OltReadingPersister
    private val mapper = jacksonObjectMapper()
    private data class Olt(val id: String, val code: String)
    private data class Known(val serial: String, val onu: String)
    private data class LiveFixture(val tenant: UUID, val collectorKey: String, val validOnu: String,
        val valid: OnuReading, val connected: OnuReading)

    private fun olt(token: String, vendor: String = "ZTE"): Olt {
        val code = "OLT-${uniq()}"
        val site: String = JsonPath.read(post("/api/sites", token,
            """{"code":"S-${uniq()}","name":"Site","location":{"longitude":106.99,"latitude":-6.24}}"""), "$.id")
        val id: String = JsonPath.read(post("/api/olts", token,
            """{"siteId":"$site","code":"$code","name":"OLT","vendor":"$vendor","managementIp":"127.0.0.1","snmpCommunity":"owned"}"""), "$.id")
        return Olt(id, code)
    }

    private fun known(token: String): Known {
        val serial = "ZTEG" + UUID.randomUUID().toString().replace("-", "").take(8).uppercase()
        return Known(serial, LegacyOnuTestFixture.stage(customer(token), serial))
    }

    private fun conflictingLegacy(token: String, serial: String) {
        check(System.getenv("WAREHOUSE_QA") == "true")
        val url = requireNotNull(System.getenv("SPRING_DATASOURCE_URL"))
        check(url == "jdbc:postgresql://127.0.0.1:25432/warehouse_test")
        val customer = UUID.fromString(customer(token))
        java.sql.DriverManager.getConnection(url, System.getenv("SPRING_FLYWAY_USER"), System.getenv("SPRING_FLYWAY_PASSWORD")).use { connection ->
            connection.autoCommit = false
            connection.prepareStatement("SELECT set_config('app.tenant_id',?,true)").use { query -> query.setString(1, tenantId(token).toString()); query.execute() }
            connection.prepareStatement("INSERT INTO onu(id,tenant_id,customer_id,serial_number,warehouse_admission) VALUES (?,?,?,?,'LEGACY_UNRESOLVED')").use { query ->
                query.setObject(1, UUID.randomUUID()); query.setObject(2, tenantId(token)); query.setObject(3, customer); query.setString(4, serial); query.executeUpdate()
            }
            connection.commit()
        }
    }

    private fun adapter(serial: String, at: Instant, profile: MibProfile = MibProfiles.ZTE): GponSnmpAdapter {
        val online = profile.statusMapping.entries.first { it.value == com.duluin.ftth.contract.OnuOperationalStatus.ONLINE }.key
        val factory = SnmpReaderFactory { _, _, _ -> object : SnmpReader {
            override fun get(oid: String): String = "owned ZTE fixture"
            override fun walkTable(columnOids: List<String>): Map<String, Map<String, String>> = mapOf("268501249.1" to mapOf(
                profile.serialNumberOid to "5A544547${serial.removePrefix("ZTEG")}", profile.statusOid to online,
                profile.rxPowerOid to (-22.5 * profile.opticalPowerDivisor).toLong().toString()))
            override fun close() {}
        } }
        return GponSnmpAdapter(profile, factory) { at }
    }

    private fun reading(device: Known, olt: Olt, at: Instant = Instant.now(), profile: MibProfile = MibProfiles.ZTE): OnuReading =
        adapter(device.serial, at, profile).pollOnus(OltTarget(olt.id, olt.code, profile.vendor, "127.0.0.1", snmpCommunity = "owned")).single()

    private fun send(key: String, body: String): JsonNode {
        val response = RestClient.builder().baseUrl("http://127.0.0.1:$port").build().post().uri("/api/collector/metrics")
            .header(CollectorProtocol.API_KEY_HEADER, key).contentType(MediaType.APPLICATION_JSON).body(body)
            .retrieve().toEntity(String::class.java)
        assertThat(response.statusCode.value()).isEqualTo(200)
        return mapper.readTree(requireNotNull(response.body))
    }

    @ParameterizedTest
    @ValueSource(strings = ["MAX", "MIN", "MALFORMED", "CURRENT"])
    fun `DISCOVERY-3 live mixed batch keeps valid sibling raw timestamp and inert replay`(clock: String) {
        val token = newTenantAdmin("v3clock")
        val target = olt(token)
        val valid = known(token)
        val unknown = Known("ZTEGFFFFFFFF", UUID.randomUUID().toString())
        val key = newCollector(token)
        val at = when (clock) { "MAX" -> Instant.MAX; "MIN" -> Instant.MIN; else -> Instant.now() }
        val bad = reading(unknown, target, at)
        val body = mapper.readTree(mapper.writeValueAsString(MetricBatch(UUID.randomUUID().toString(), Instant.now(),
            listOf(bad, reading(valid, target))))) as ObjectNode
        if (clock == "MALFORMED") (body.path("readings")[0] as ObjectNode).put("observedAt", "not-a-time")
        val rawTime = body.path("readings")[0].path("observedAt").asString()
        val payload = mapper.writeValueAsString(body)
        assertThat(send(key, payload).path("accepted").asInt()).isEqualTo(1)
        assertThat(send(key, payload).path("duplicate").asBoolean()).isTrue()
        assertThat(scalar(token, "SELECT count(*) FROM onu_metric WHERE onu_id='${valid.onu}'")).isEqualTo("1")
        assertThat(scalar(token, "SELECT count(*) FROM ingest_batch")).isEqualTo("1")
        assertThat(scalar(token, "SELECT count(*) FROM monitoring_unassigned_observation")).isEqualTo("1")
        assertThat(scalar(token, "SELECT payload->>'observedAt' FROM monitoring_unassigned_observation")).isEqualTo(rawTime)
        assertThat(scalar(token, "SELECT count(*) FROM monitoring_unassigned_observation WHERE observed_at IS NULL"))
            .isEqualTo(if (clock == "CURRENT") "0" else "1")
        assertThat(scalar(token, "SELECT count(*) FROM inventory_serialized_asset")).isEqualTo("0")
    }

    @ParameterizedTest
    @CsvSource("COLLECTOR,ZTE", "SERVER,ZTE", "COLLECTOR,HUAWEI", "SERVER,HUAWEI", "COLLECTOR,FIBERHOME", "SERVER,FIBERHOME")
    fun `T3 real GPON producer accepts unique active legacy unattached episode only`(route: String, vendor: String) {
        val token = newTenantAdmin("v3producer")
        val profile = MibProfiles.all().single { it.vendor == vendor }
        val target = olt(token, vendor)
        val device = known(token)
        val sample = reading(device, target, profile = profile)
        assertThat(sample.pathProvenance).isEqualTo(OnuPathProvenance.UNVERIFIED_INDEX)
        assertThat(sample.ponPortLabel).isEqualTo("268501249.1")
        assertThat(sample.rxPowerDbm).isEqualTo(-22.5)
        if (route == "COLLECTOR") {
            val key = newCollector(token)
            assertThat(send(key, mapper.writeValueAsString(MetricBatch(UUID.randomUUID().toString(), Instant.now(), listOf(sample)))).path("accepted").asInt()).isEqualTo(1)
        } else TenantContext.runAs(tenantId(token)) {
            ServerSideOltPoller(network, AdapterRegistry(listOf(adapter(device.serial, Instant.now(), profile))), persister).pollTenant(tenantId(token))
        }
        assertThat(scalar(token, "SELECT count(*) FROM onu_metric WHERE onu_id='${device.onu}'")).isEqualTo("1")
        assertThat(scalar(token, "SELECT status FROM onu WHERE id='${device.onu}'")).isEqualTo("ONLINE")
        assertThat(scalar(token, "SELECT attribution->>'decision' FROM onu_metric")).isEqualTo("EPISODE_ONLY")
        assertThat(scalar(token, "SELECT attribution->>'source' FROM onu_metric")).isEqualTo(if (route == "COLLECTOR") "COLLECTOR" else "SERVER_POLL")
        assertThat(scalar(token, "SELECT count(*) FROM onu_metric WHERE attribution_verified")).isEqualTo("0")
        assertThat(scalar(token, "SELECT count(*) FROM inventory_serialized_asset")).isEqualTo("0")
    }

    private fun connect(token: String, target: Olt, onu: String) {
        fun id(value: String): String = JsonPath.read(value, "$.id")
        val pon = id(post("/api/olts/${target.id}/pon-ports", token, """{"label":"1/1/1"}"""))
        val odc = id(post("/api/odcs", token, """{"code":"C-${uniq()}","name":"ODC","ponPortId":"$pon","capacity":8,"splitterRatio":"1:8","location":{"longitude":106.99,"latitude":-6.24}}"""))
        val odp = id(post("/api/odps", token, """{"code":"P-${uniq()}","name":"ODP","odcId":"$odc","capacity":8,"splitterRatio":"1:8","location":{"longitude":106.99,"latitude":-6.24}}"""))
        assertThat(mockMvc.perform(post("/api/customers/onus/$onu/attach").header("Authorization", "Bearer $token")
            .contentType(MediaType.APPLICATION_JSON).content("""{"odpId":"$odp","portNumber":1}""")).andReturn().response.status).isEqualTo(200)
    }

    @ParameterizedTest
    @ValueSource(strings = ["UNKNOWN", "FOREIGN_EPISODE", "FOREIGN_OLT", "UNKNOWN_OLT", "AMBIGUOUS", "CONNECTED", "PATH_MISMATCH"])
    fun `T3 live producer denial controls cannot invent stock or verified path`(scenario: String) {
        val token = newTenantAdmin("v3denial")
        val target = olt(token)
        val device = when (scenario) {
            "UNKNOWN" -> Known("ZTEGFFFFFFFF", UUID.randomUUID().toString())
            "FOREIGN_EPISODE" -> known(newTenantAdmin("foreignonu"))
            else -> known(token)
        }
        if (scenario == "AMBIGUOUS") conflictingLegacy(token, device.serial)
        if (scenario in setOf("CONNECTED", "PATH_MISMATCH")) connect(token, target, device.onu)
        val reported = when (scenario) {
            "FOREIGN_OLT" -> olt(newTenantAdmin("foreignolt"))
            "UNKNOWN_OLT" -> target.copy(code = "MISSING-OLT")
            else -> target
        }
        val sample = reading(device, reported).let {
            if (scenario == "PATH_MISMATCH") it.copy(pathProvenance = OnuPathProvenance.CONFIGURED_LABEL, ponPortLabel = "1/1/2") else it
        }
        val key = newCollector(token)
        assertThat(send(key, mapper.writeValueAsString(MetricBatch(UUID.randomUUID().toString(), Instant.now(), listOf(sample)))).path("accepted").asInt()).isZero()
        assertThat(scalar(token, "SELECT count(*) FROM onu_metric")).isEqualTo("0")
        assertThat(scalar(token, "SELECT count(*) FROM monitoring_unassigned_observation")).isEqualTo("1")
        assertThat(scalar(token, "SELECT count(*) FROM inventory_serialized_asset")).isEqualTo("0")
    }

    @Test
    fun `prepare scoped fixtures for standalone live ingestion proof`() {
        check(System.getenv("WAREHOUSE_QA") == "true")
        val token = newTenantAdmin("v3manual")
        val target = olt(token)
        val valid = known(token)
        val connected = known(token)
        connect(token, target, connected.onu)
        val fixture = LiveFixture(tenantId(token), newCollector(token), valid.onu, reading(valid, target), reading(connected, target))
        val runtime = java.nio.file.Path.of(System.getProperty("user.dir")).parent.resolve(".omo/runtime")
        val file = runtime.resolve("task23-v3-live.json")
        check(!java.nio.file.Files.isSymbolicLink(runtime) && !java.nio.file.Files.isSymbolicLink(file))
        if (!java.nio.file.Files.exists(file)) java.nio.file.Files.createFile(file,
            java.nio.file.attribute.PosixFilePermissions.asFileAttribute(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")))
        java.nio.file.Files.writeString(file, mapper.writeValueAsString(fixture))
        assertThat(scalar(token, "SELECT count(*) FROM inventory_serialized_asset")).isEqualTo("0")
    }
}

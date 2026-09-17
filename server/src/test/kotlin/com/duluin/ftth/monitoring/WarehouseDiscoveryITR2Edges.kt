package com.duluin.ftth.monitoring

import com.duluin.ftth.contract.MetricBatch
import com.duluin.ftth.contract.OnuOperationalStatus
import com.duluin.ftth.contract.OnuReading
import com.jayway.jsonpath.JsonPath
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.sql.SQLException
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class WarehouseDiscoveryITR2Edges : WarehouseDiscoveryFixture() {
    @Autowired private lateinit var dataSource: DataSource
    private data class Path(val odp: String, val odc: String, val olt: String, val code: String)
    private fun path(token: String, code: String): Path {
        fun id(value: String): String = JsonPath.read(value, "$.id")
        val site = id(post("/api/sites", token, """{"code":"S-$code","name":"Site","location":{"longitude":106.99,"latitude":-6.24}}"""))
        val olt = id(post("/api/olts", token, """{"siteId":"$site","code":"O-$code","name":"OLT","vendor":"ZTE","managementIp":"127.0.0.1","snmpCommunity":"owned"}"""))
        val pon = id(post("/api/olts/$olt/pon-ports", token, """{"label":"1/1/1"}"""))
        val odc = id(post("/api/odcs", token, """{"code":"C-$code","name":"ODC","ponPortId":"$pon","capacity":8,"splitterRatio":"1:8","location":{"longitude":106.99,"latitude":-6.24}}"""))
        val odp = id(post("/api/odps", token, """{"code":"P-$code","name":"ODP","odcId":"$odc","capacity":8,"splitterRatio":"1:8","location":{"longitude":106.99,"latitude":-6.24}}"""))
        return Path(odp, odc, olt, "O-$code")
    }

    @ParameterizedTest
    @ValueSource(strings = ["EMPTY", "UNRELATED", "DUPLICATE", "REORDERED", "SUPERSEDED", "DELETED", "OLT", "VALID"])
    fun `DB-R2-2 BOUND evidence must contain exactly the selected nondeleted historical chain`(mode: String) {
        val token = newTenantAdmin("r2edges")
        val device = legacy(token)
        val first = path(token, "FIRST")
        val second = path(token, "SECOND")
        val unused = path(token, "UNUSED")
        assertThat(mockMvc.perform(put("/api/odps/${first.odp}/uplink").header("Authorization", "Bearer $token")
            .contentType(MediaType.APPLICATION_JSON).content("""{"targetId":"${second.odc}"}""")).andReturn().response.status).isEqualTo(200)
        assertThat(mockMvc.perform(delete("/api/odps/${unused.odp}").header("Authorization", "Bearer $token")).andReturn().response.status).isEqualTo(204)
        assertThat(mockMvc.perform(post("/api/customers/onus/${device.onu}/attach").header("Authorization", "Bearer $token")
            .contentType(MediaType.APPLICATION_JSON).content("""{"odpId":"${first.odp}","portNumber":1}""")).andReturn().response.status).isEqualTo(200)
        val mapper = jacksonObjectMapper()
        val key = newCollector(token)
        postAsCollector("/api/collector/metrics", key, mapper.writeValueAsString(MetricBatch(UUID.randomUUID().toString(), Instant.now(),
            listOf(OnuReading(device.serial, second.code, "1/1/1", OnuOperationalStatus.ONLINE, -20.0, null, null, null, Instant.now())))))
        val attribution = mapper.readTree(scalar(token, "SELECT attribution::text FROM onu_metric WHERE onu_id='${device.onu}'")) as tools.jackson.databind.node.ObjectNode
        val ids = attribution.path("networkEdgeIds").asSequence().map { it.asLong() }.toList()
        val replacement: List<Long> = when (mode) {
            "EMPTY" -> emptyList()
            "UNRELATED" -> listOf(scalar(token, "SELECT min(id) FROM network_observation_edge WHERE node_id='${second.odp}'").toLong())
            "DUPLICATE" -> ids + ids.first()
            "REORDERED" -> ids.reversed()
            "SUPERSEDED" -> listOf(scalar(token, "SELECT min(id) FROM network_observation_edge WHERE node_id='${first.odp}'").toLong()) + ids.drop(1)
            "DELETED" -> listOf(scalar(token, "SELECT max(id) FROM network_observation_edge WHERE node_id='${unused.odp}'").toLong()) + ids.drop(1)
            else -> ids
        }
        attribution.putArray("networkEdgeIds").also { array -> replacement.forEach { array.add(it) } }
        val insert = {
            dataSource.connection.use { connection ->
                connection.autoCommit = false
                try {
                    connection.createStatement().use { it.execute("SET LOCAL app.tenant_id='${tenantId(token)}'") }
                    connection.prepareStatement("""INSERT INTO onu_metric(time,tenant_id,onu_id,olt_id,status,attribution)
                        SELECT time+interval '1 microsecond',tenant_id,onu_id,?::uuid,status,?::jsonb FROM onu_metric WHERE onu_id=?::uuid LIMIT 1""").use { query ->
                        query.setString(1, if (mode == "OLT") first.olt else second.olt); query.setString(2, mapper.writeValueAsString(attribution)); query.setString(3, device.onu); query.executeUpdate()
                    }
                    connection.commit()
                } finally { connection.rollback() }
            }
        }
        if (mode == "VALID") insert() else assertThat(assertThrows<SQLException> { insert() }.sqlState).isEqualTo("23514")
    }
}

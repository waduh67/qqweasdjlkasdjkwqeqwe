package com.duluin.ftth.monitoring

import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.contract.OnuOperationalStatus
import com.duluin.ftth.contract.OnuReading
import com.duluin.ftth.cpe.application.port.outbound.AcsDevice
import com.duluin.ftth.cpe.application.service.CpeSyncService
import com.duluin.ftth.customer.CustomerAssetEpisodeFixture
import com.duluin.ftth.customer.CustomerDeploymentFixture
import com.duluin.ftth.monitoring.application.service.MetricIngestionService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

private fun manualManifest(name: String, values: Map<String, String>) {
    check(System.getenv("WAREHOUSE_QA") == "true")
    check(System.getenv("SPRING_DATASOURCE_URL") == "jdbc:postgresql://127.0.0.1:25432/warehouse_test")
    val runtime = Path.of(System.getProperty("user.dir")).parent.resolve(".omo/runtime")
    check(!Files.isSymbolicLink(runtime))
    val file = runtime.resolve(name)
    check(!Files.isSymbolicLink(file))
    if (!Files.exists(file)) Files.createFile(file, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
    Files.writeString(file, jacksonObjectMapper().writeValueAsString(values))
    val classpath = requireNotNull(System.getProperty("warehouse.test.classpath")).split(java.io.File.pathSeparator)
        .filterNot { it.contains("/simulator/") || it.contains("/collector/") || it.contains("/server/build/classes/kotlin/test") || it.contains("/server/build/resources/test") }
        .joinToString(java.io.File.pathSeparator)
    Files.writeString(runtime.resolve("task23-manual-classpath.txt"), classpath)
}

class WarehouseDiscoveryManualSeedIT : CustomerDeploymentFixture() {
    @Test
    fun `prepare issued source through warehouse workflows for built HTTP proof`() {
        val installation = installation(extraPermissions = setOf("monitoring.provisioning.manage"))
        val foreign = installation()
        val stock = fixture(installation.receipt.stock.token)
        val serial = requireNotNull(installation.receipt.input.lines.single().serial)
        stock.transaction {
            context.getBean(MetricIngestionService::class.java).ingestReadings(tenant,
                listOf(OnuReading(serial, "OLT-X", null, OnuOperationalStatus.ONLINE, -20.0, null, null, null, Instant.now())))
        }
        val discovery = stock.transaction { scalar("SELECT id FROM discovered_onu WHERE serial_number='$serial'") }
        val collector = request("POST", "/api/monitoring/collectors", installation.receipt.stock.token,
            """{"name":"Manual discovery","pollIntervalSeconds":60}""")
        assertThat(collector.status).isEqualTo(201)
        val admin = mapper.readTree(request("GET", "/api/me", installation.receipt.stock.token).contentAsString)
        val actor = mapper.readTree(request("GET", "/api/me", installation.receipt.receiver.first).contentAsString)
        manualManifest("task23-manual-deployment.json", mapOf(
            "tenant" to stock.tenant.toString(), "customer" to installation.customer.toString(), "serial" to serial,
            "discovery" to discovery, "authorization" to installation.authorization.toString(),
            "foreignAuthorization" to foreign.authorization.toString(),
            "adminEmail" to admin.path("email").asString(), "actorEmail" to actor.path("email").asString(),
            "slug" to admin.path("email").asString().substringAfter('@').substringBefore(".test"),
            "collectorKey" to mapper.readTree(collector.contentAsString).path("apiKey").asString(),
        ))
        assertUninstalled(installation)
    }
}

class WarehouseDiscoveryManualEpisodesIT : CustomerAssetEpisodeFixture() {
    @Test
    fun `prepare retained A and current B episode fixture for built history and ACS proof`() {
        prepareEpisodes()
    }

    internal fun prepareEpisodes(): String {
        val serial = "MANUAL-${UUID.randomUUID()}".uppercase()
        val fixture = episodeCase(listOf(serial, "$serial-SPARE"))
        val start = Instant.now().minusSeconds(3600).truncatedTo(ChronoUnit.MILLIS)
        val boundary = start.plusSeconds(1800)
        val first = UUID.randomUUID()
        val second = UUID.randomUUID()
        fixture.stock.transaction { sql(fixture.assignment(first, start = start.toString())); sql(fixture.episode(first, start = start.toString())) }
        val sync = context.getBean(CpeSyncService::class.java)
        val old = AcsDevice("MANUAL-${fixture.asset}", fixture.serial, "00AABB", "Model", "Vendor", "A-model", null,
            "192.0.2.10", boundary.minusSeconds(10), "A-private", observedFieldsAt = boundary.minusSeconds(10))
        TenantContext.runAs(fixture.stock.tenant) { sync.sync(listOf(old)) }
        val oldCpe = fixture.stock.transaction { scalar("SELECT id FROM cpe_device") }
        fixture.stock.transaction {
            sql("UPDATE onu SET retired_at='$boundary',episode_revision=episode_revision+1 WHERE assignment_id='$first'")
            sql("UPDATE inventory_asset_assignment SET ended_at='$boundary',revision=revision+1 WHERE id='$first'")
        }
        fixture.stock.transaction { sql(fixture.assignment(second, fixture.customerB, boundary.toString())); sql(fixture.episode(second, fixture.customerB, boundary.toString())) }
        TenantContext.runAs(fixture.stock.tenant) { sync.sync(listOf(old.copy(model = "B-model", ipAddress = "192.0.2.20", ssid = "B-private",
            lastInformAt = boundary.plusSeconds(10), observedFieldsAt = boundary.plusSeconds(10)))) }
        val collector = request("POST", "/api/monitoring/collectors", fixture.token, """{"name":"Manual temporal","pollIntervalSeconds":60}""")
        assertThat(collector.status).isEqualTo(201)
        val admin = mapper.readTree(request("GET", "/api/me", fixture.token).contentAsString)
        fixture.stock.transaction {
            manualManifest("task23-manual-episodes.json", mapOf(
                "tenant" to tenant.toString(), "serial" to fixture.serial, "customerA" to fixture.customerA.toString(), "customerB" to fixture.customerB.toString(),
                "onuA" to scalar("SELECT id FROM onu WHERE assignment_id='$first'"), "onuB" to scalar("SELECT id FROM onu WHERE assignment_id='$second'"),
                "cpeA" to oldCpe, "cpeB" to scalar("SELECT id FROM cpe_device WHERE customer_id='${fixture.customerB}'"),
                "boundary" to boundary.toString(), "genieacsId" to old.genieacsId,
                "collectorKey" to mapper.readTree(collector.contentAsString).path("apiKey").asString(),
                "adminEmail" to admin.path("email").asString(), "slug" to admin.path("email").asString().substringAfter('@').substringBefore(".test"),
            ))
            assertThat(scalar("SELECT count(*) FROM inventory_asset_assignment WHERE ended_at IS NULL")).isEqualTo("1")
            assertThat(scalar("SELECT count(*) FROM cpe_device")).isEqualTo("2")
        }
        return serial
    }
}

class WarehouseDiscoveryManualConflictIT : CustomerDeploymentFixture() {
    @Test
    fun `prepare an issued competing tenant owner without consuming it before live QA`() {
        // Each invocation owns both tenants; a full suite must not depend on
        // another test class having generated a private manifest first.
        val owner = WarehouseDiscoveryManualEpisodesIT()
        context.autowireCapableBeanFactory.autowireBean(owner)
        val serial = owner.prepareEpisodes()
        val installation = installation(serials = listOf(serial, "$serial-OTHER"))
        val stock = fixture(installation.receipt.stock.token)
        val admin = mapper.readTree(request("GET", "/api/me", installation.receipt.stock.token).contentAsString)
        val actor = mapper.readTree(request("GET", "/api/me", installation.receipt.receiver.first).contentAsString)
        manualManifest("task23-manual-conflict.json", mapOf(
            "tenant" to stock.tenant.toString(), "customer" to installation.customer.toString(), "serial" to serial,
            "authorization" to installation.authorization.toString(), "operation" to installation.operation.toString(),
            "adminEmail" to admin.path("email").asString(), "actorEmail" to actor.path("email").asString(),
            "slug" to admin.path("email").asString().substringAfter('@').substringBefore(".test"),
        ))
        assertUninstalled(installation)
    }
}

class WarehouseDiscoveryManualStableIT : CustomerDeploymentFixture() {
    @Test
    fun `prepare connected warehouse owner for live diagnostic generations and exact path proof`() {
        val serial = "R2-STABLE-${UUID.randomUUID()}".uppercase()
        val installation = installation(serials = listOf(serial, "$serial-X"))
        val token = installation.receipt.stock.token
        fun created(path: String, body: String): String {
            val response = request("POST", path, token, body)
            assertThat(response.status).withFailMessage(response.contentAsString).isEqualTo(201)
            return mapper.readTree(response.contentAsString).path("id").asString()
        }
        val site = created("/api/sites", """{"code":"R2SITE","name":"Site","location":{"longitude":106.99,"latitude":-6.24}}""")
        val olt = created("/api/olts", """{"siteId":"$site","code":"R2OLT","name":"OLT","vendor":"ZTE","managementIp":"127.0.0.1","snmpCommunity":"owned"}""")
        val pon = created("/api/olts/$olt/pon-ports", """{"label":"1/1/1"}""")
        val odc = created("/api/odcs", """{"code":"R2ODC","name":"ODC","ponPortId":"$pon","capacity":8,"splitterRatio":"1:8","location":{"longitude":106.99,"latitude":-6.24}}""")
        val odp = created("/api/odps", """{"code":"R2ODP","name":"ODP","odcId":"$odc","capacity":8,"splitterRatio":"1:8","location":{"longitude":106.99,"latitude":-6.24}}""")
        val installed = request("POST", "/api/customers/${installation.customer}/assets/install", installation.receipt.receiver.first,
            """{"authorizationId":"${installation.authorization}","expectedRevision":0,"topology":{"odpId":"$odp","portNumber":1,"installRxPowerDbm":null}}""", "stable-install")
        assertThat(installed.status).withFailMessage(installed.contentAsString).isEqualTo(201)
        val stock = fixture(token)
        val time = Instant.now()
        val genie = "R2-STABLE-${installation.operation}"
        TenantContext.runAs(stock.tenant) { context.getBean(CpeSyncService::class.java).sync(listOf(
            AcsDevice(genie, serial, null, null, "Vendor", "Model", null, null, time, "stable", observedFieldsAt = time))) }
        val collector = request("POST", "/api/monitoring/collectors", token, """{"name":"Stable","pollIntervalSeconds":60}""")
        assertThat(collector.status).isEqualTo(201)
        val admin = mapper.readTree(request("GET", "/api/me", token).contentAsString)
        manualManifest("task23-manual-stable.json", mapOf(
            "tenant" to stock.tenant.toString(), "serial" to serial, "customer" to installation.customer.toString(),
            "onu" to installation.operation.toString(), "cpe" to stock.transaction { scalar("SELECT id FROM cpe_device") },
            "genieacsId" to genie, "oltId" to olt, "oltCode" to "R2OLT", "ponPortLabel" to "1/1/1",
            "adminEmail" to admin.path("email").asString(), "slug" to admin.path("email").asString().substringAfter('@').substringBefore(".test"),
            "collectorKey" to mapper.readTree(collector.contentAsString).path("apiKey").asString(),
        ))
    }
}

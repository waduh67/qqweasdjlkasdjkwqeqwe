package com.duluin.ftth.inventory

import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.UUID
import java.util.concurrent.TimeUnit

@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class WarehouseQueryITRestart : WarehouseReceiptHttpFixture() {
    companion object {
        private val database by lazy { WarehouseSchemaDatabase() }
        @JvmStatic @DynamicPropertySource fun properties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { database.url }
            registry.add("spring.flyway.url") { database.url }
            registry.add("spring.flyway.schemas") { database.schema }
            registry.add("spring.flyway.default-schema") { database.schema }
        }
        @JvmStatic @AfterAll fun cleanup() { database.close() }
    }
    @TempDir lateinit var temporary: Path
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()

    @Test fun `SIGKILL fresh process preserves exact summaries duplicate-name pages timeline and split tree`() {
        val setup = setupReceipt()
        assertThat(request("PUT", "/api/v1/warehouse/skus/${setup.cable}", setup.token,
            """{"expectedRevision":0,"code":"CABLE","name":"Cable","tracking":"LOT","baseUnit":"MM","inspectionRequired":false}""").status).isEqualTo(200)
        val serials = (1..30).joinToString(",") { """{"serial":"RESTART-$it"}""" }
        val receipt = draft(setup, """{"skuId":"${setup.cable}","quantityBase":"1000000","lotCode":"R"},
            {"skuId":"${setup.onu}","quantityBase":"30","serials":[$serials]}""")
        val receiptId = receipt.path("id").asString()
        transition(setup, receiptId, "receive", """{"expectedRevision":0}""")
        val window = fixture(setup.token).transaction { jdbc { connection -> connection.createStatement().use { statement ->
            statement.executeQuery("SELECT received_at,clock_timestamp() FROM inventory_lot").use { rows ->
                rows.next(); rows.getTimestamp(1).toInstant().minusSeconds(1) to rows.getTimestamp(2).toInstant()
            }
        } } }
        val range = "from=${window.first}&until=${window.second}"
        val detail = mapper.readTree(request("GET", "/api/v1/warehouse/receipts/$receiptId", setup.token).contentAsString)
        val line = detail.path("lines")[0]
        transition(setup, receiptId, "putaway", """{"expectedRevision":1,"destinationLocationId":"${setup.bin}","lines":[
            {"lineId":"${line.path("id").asString()}","stockIdentityId":"${line.path("pieces")[0].path("stockIdentityId").asString()}","quantityBase":"82501","baseUnit":"MM"}]}""")
        val lot = mapper.readTree(request("GET", "/api/v1/warehouse/lots", setup.token).contentAsString).path("items")[0].path("id").asString()
        val asset = mapper.readTree(request("GET", "/api/v1/warehouse/assets/lookup?value=RESTART-1", setup.token).contentAsString).path("assetId").asString()
        val position = mapper.readTree(request("GET", "/api/v1/warehouse/stock/positions?serial=RESTART-1", setup.token).contentAsString).path("items")[0].path("id").asString()
        fixture(setup.token).transaction { sql("UPDATE inventory_balance_projection SET updated_at='${window.second.plusSeconds(86400)}',revision=revision+1 WHERE id='$position'") }
        val paths = listOf("stock", "stock/positions", "assets?sort=name", "assets?sort=name&page=1", "assets/$asset", "assets/$asset/history", "lots/$lot", "lots/$lot/segments", "lots/$lot/history",
            "lots?$range", "stock/positions/$position/history?serial=restart-1&$range", "assets/$asset/history?serial=restart-1&$range")
        val rejected = listOf("stock/positions/$position/history?serial=NOT-THE-ASSET", "assets/$asset/history?serial=NOT-THE-ASSET")
        val first = start("query-first")
        val snapshots = try { (paths + rejected).associateWith { get(first.second, setup.token, it, if (it in rejected) 404 else 200) } } finally { stop(first.first) }
        assertThat(mapper.readTree(snapshots.getValue("lots?$range")).path("totalElements").asInt()).isEqualTo(1)
        assertThat(mapper.readTree(snapshots.getValue("stock/positions/$position/history?serial=restart-1&$range")).path("totalElements").asInt()).isEqualTo(2)
        val firstPage = mapper.readTree(snapshots.getValue("assets?sort=name"))
        val secondPage = mapper.readTree(snapshots.getValue("assets?sort=name&page=1"))
        assertThat(firstPage.path("items").size()).isEqualTo(25)
        assertThat(secondPage.path("items").size()).isEqualTo(5)
        assertThat((firstPage.path("items").asSequence()+secondPage.path("items").asSequence()).map { it.path("id").asString() }.toSet()).hasSize(30)
        val restarted = start("query-second")
        try { (paths + rejected).forEach { assertThat(get(restarted.second,setup.token,it,if (it in rejected) 404 else 200)).describedAs(it).isEqualTo(snapshots.getValue(it)) } }
        finally { stop(restarted.first) }
    }

    @Test fun `query during partially applied receive sees only before or committed after snapshot`() {
        val setup = setupReceipt()
        val receipt = draft(setup, """{"skuId":"${setup.cable}","quantityBase":"1000000","lotCode":"R"},
            {"skuId":"${setup.onu}","quantityBase":"1","serials":[{"serial":"CONCURRENT"}]}""")
        val id = receipt.path("id").asString()
        val marker = "query_pause_" + UUID.randomUUID().toString().replace("-", "")
        val key = UUID.randomUUID().leastSignificantBits
        val owner = context.getBean(org.flywaydb.core.Flyway::class.java).configuration.dataSource
        val process = start(marker)
        try { owner.connection.use { lock ->
            lock.createStatement().use { statement ->
                statement.execute("SELECT pg_advisory_lock($key)")
                statement.execute("CREATE FUNCTION $marker() RETURNS trigger LANGUAGE plpgsql AS 'BEGIN PERFORM pg_advisory_xact_lock($key); RETURN NEW; END'")
                statement.execute("CREATE TRIGGER $marker AFTER UPDATE ON inventory_balance_projection FOR EACH ROW WHEN (NEW.sku_id='${setup.cable}' AND NEW.quantity_base>0) EXECUTE FUNCTION $marker()")
            }
            try {
                val pending = client.sendAsync(HttpRequest.newBuilder(URI("http://127.0.0.1:${process.second}/api/v1/warehouse/receipts/$id/receive"))
                    .timeout(Duration.ofSeconds(40)).header("Authorization", "Bearer ${setup.token}").header("Idempotency-Key", marker)
                    .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("""{"expectedRevision":0}""")).build(),HttpResponse.BodyHandlers.ofString())
                context.getBean(javax.sql.DataSource::class.java).connection.use { observer ->
                    await().atMost(Duration.ofSeconds(20)).until {
                        check(!pending.isDone) { "Receive completed before pause: ${pending.get().body()}" }
                        observer.createStatement().use { statement ->
                            statement.executeQuery("SELECT count(*) FROM pg_stat_activity WHERE application_name='$marker' AND wait_event='advisory'").use { rows -> rows.next(); rows.getInt(1)==1 }
                        }
                    }
                    lock.createStatement().use { statement ->
                        statement.executeQuery("SELECT count(*) FROM pg_stat_activity WHERE application_name='$marker' AND wait_event='advisory'").use { rows ->
                            rows.next(); assertThat(rows.getInt(1)).describedAs("migration owner cannot observe app role wait state").isZero()
                        }
                    }
                }
                repeat(3) { assertThat(mapper.readTree(get(process.second,setup.token,"stock")).path("totalElements").asInt()).isZero() }
                lock.createStatement().use { it.execute("SELECT pg_advisory_unlock($key)") }
                assertThat(pending.get(30,TimeUnit.SECONDS).statusCode()).isEqualTo(200)
                assertThat(mapper.readTree(get(process.second,setup.token,"stock")).path("totalElements").asInt()).isEqualTo(2)
            } finally { lock.createStatement().use { statement ->
                statement.execute("SELECT pg_advisory_unlock($key)")
                statement.execute("DROP TRIGGER $marker ON inventory_balance_projection")
                statement.execute("DROP FUNCTION $marker()")
            } }
        } } finally { stop(process.first) }
    }

    private fun get(port: Int, token: String, path: String, expected: Int = 200): String {
        val response = client.send(HttpRequest.newBuilder(URI("http://127.0.0.1:$port/api/v1/warehouse/$path"))
            .timeout(Duration.ofSeconds(20)).header("Authorization","Bearer $token").GET().build(),HttpResponse.BodyHandlers.ofString())
        assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(expected)
        return response.body()
    }
    private fun start(name: String): Pair<Process,Int> {
        val port = temporary.resolve("$name.port")
        val process = ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-Xmx512m","-cp",
            requireNotNull(System.getProperty("warehouse.test.classpath")),WarehouseReceiptRestartProcess::class.java.name,
            port.toString(),name,database.url,database.schema).redirectErrorStream(true).redirectOutput(temporary.resolve("$name.log").toFile()).start()
        try {
            await().atMost(Duration.ofSeconds(100)).until { check(process.isAlive) { Files.readString(temporary.resolve("$name.log")) }; Files.exists(port) }
            return process to Files.readString(port).toInt()
        } catch (failure: Exception) { stop(process); throw failure }
    }
    private fun stop(process: Process) { process.destroyForcibly(); assertThat(process.waitFor(15,TimeUnit.SECONDS)).isTrue() }
}

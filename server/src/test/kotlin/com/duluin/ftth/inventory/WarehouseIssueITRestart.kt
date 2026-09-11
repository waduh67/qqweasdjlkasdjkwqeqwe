package com.duluin.ftth.inventory

import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.net.Socket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class WarehouseIssueITRestart : WarehouseIssueFixture() {
    companion object {
        private val database by lazy { WarehouseSchemaDatabase() }
        @JvmStatic @DynamicPropertySource fun database(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { database.url }
            registry.add("spring.flyway.url") { database.url }
            registry.add("spring.flyway.schemas") { database.schema }
            registry.add("spring.flyway.default-schema") { database.schema }
        }
        @JvmStatic @AfterAll fun cleanup() { database.close() }
    }
    @TempDir lateinit var temporary: Path

    @Test fun `response loss and SIGKILL replay exactly one cable cut and dispatch with identical slip`() {
        val setup = issuedSetup()
        val pick = pickBody(setup)
        val first = start("pick")
        try {
            loseResponse(first.second, setup, "pick", pick)
            await().atMost(Duration.ofSeconds(30)).until { fixture(setup.stock.token).transaction {
                scalar("SELECT count(*) FROM inventory_operation WHERE business_action='PICK'") == "1"
            } }
        } finally { stop(first.first) }
        val pickedBody = fixture(setup.stock.token).transaction { scalar("SELECT original_body FROM inventory_operation WHERE business_action='PICK'") }
        val issue = mapper.readTree(pickedBody)
        val dispatch = transitionBody(setup, issue)
        val second = start("dispatch")
        try {
            val replay = http(second.second, setup, "pick", pick)
            assertThat(replay.statusCode()).withFailMessage(replay.body()).isEqualTo(200)
            assertThat(replay.body()).isEqualTo(pickedBody)
            loseResponse(second.second, setup, "dispatch", dispatch)
            await().atMost(Duration.ofSeconds(30)).until { fixture(setup.stock.token).transaction {
                scalar("SELECT count(*) FROM inventory_operation WHERE business_action='DISPATCH'") == "1"
            } }
        } finally { stop(second.first) }
        val original = fixture(setup.stock.token).transaction { scalar("SELECT original_body FROM inventory_operation WHERE business_action='DISPATCH'") }
        val third = start("replay")
        try {
            val replay = http(third.second, setup, "dispatch", dispatch)
            assertThat(replay.statusCode()).withFailMessage(replay.body()).isEqualTo(200)
            assertThat(replay.body()).isEqualTo(original)
            val slip = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI("http://127.0.0.1:${third.second}/api/work-orders/${setup.workOrder}/materials/issues/${issue.path("issueId").asString()}/slip"))
                .timeout(Duration.ofSeconds(30)).header("Authorization", "Bearer ${setup.stock.token}").GET().build(), HttpResponse.BodyHandlers.ofString())
            assertThat(slip.statusCode()).isEqualTo(200)
            assertThat(slip.body()).isEqualTo(original)
            fixture(setup.stock.token).transaction {
                assertThat(scalar("SELECT count(*) FROM inventory_segment WHERE state='SPLIT'")).isEqualTo("1")
                assertThat(scalar("SELECT count(*) FROM inventory_reservation")).isEqualTo("1")
                assertThat(scalar("SELECT count(*) FROM inventory_movement WHERE kind='ISSUE'")).isEqualTo("1")
                assertThat(scalar("SELECT sum(quantity_base) FROM inventory_balance_projection WHERE location_id='${setup.stock.bin}'")).isEqualTo("900000")
            }
        } finally { stop(third.first) }
    }
    private fun loseResponse(port: Int, setup: IssueSetup, action: String, body: String) {
        Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 5000
            socket.getOutputStream().write(("POST /api/work-orders/${setup.workOrder}/materials/$action HTTP/1.1\r\nHost: localhost\r\nAuthorization: Bearer ${setup.stock.token}\r\n" +
                "Idempotency-Key: lost-$action\r\nContent-Type: application/json\r\nContent-Length: ${body.toByteArray().size}\r\nConnection: close\r\n\r\n$body").toByteArray())
            socket.getOutputStream().flush()
        }
    }
    private fun http(port: Int, setup: IssueSetup, action: String, body: String) = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build().send(
        HttpRequest.newBuilder(URI("http://127.0.0.1:$port/api/work-orders/${setup.workOrder}/materials/$action")).timeout(Duration.ofSeconds(30))
            .header("Authorization", "Bearer ${setup.stock.token}").header("Idempotency-Key", "lost-$action").header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString())
    private fun start(name: String): Pair<Process, Int> {
        val database = context.getBean(DataSource::class.java).connection.use { it.metaData.url to it.schema }
        val port = temporary.resolve("$name.port")
        val log = temporary.resolve("$name.log")
        val process = ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-Xmx512m", "-cp",
            requireNotNull(System.getProperty("warehouse.test.classpath")), WarehouseReceiptRestartProcess::class.java.name,
            port.toString(), "issue-$name", database.first, database.second).redirectErrorStream(true).redirectOutput(log.toFile()).start()
        try {
            await().atMost(Duration.ofSeconds(180)).until { check(process.isAlive) { Files.readString(log) }; Files.exists(port) }
            return process to Files.readString(port).toInt()
        } catch (failure: Exception) { stop(process); throw AssertionError("Child startup failed: ${Files.readString(log)}", failure) }
    }
    private fun stop(process: Process) { process.destroyForcibly(); assertThat(process.waitFor(15, TimeUnit.SECONDS)).isTrue() }
}

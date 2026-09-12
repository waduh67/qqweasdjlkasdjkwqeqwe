package com.duluin.ftth.fulfillment

import com.duluin.ftth.inventory.WarehouseReceiptRestartProcess
import com.duluin.ftth.inventory.WarehouseSchemaDatabase
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.io.TempDir
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
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.annotation.DirtiesContext

@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class WorkOrderMaterialsITRestart : MaterialWorkflowFixture() {
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
    @Test fun `lost submit response and SIGKILL preserve one demand and the exact durable response`() {
        val setup = setupReceipt()
        val id = workOrder(setup.token)
        putPlan(setup.token, id, plan(setup.token, id, "[${line(setup.cable)}]"), "restart-plan")
        val payload = command(setup.token, id, 1)
        val first = start("submit")
        try {
            Socket("127.0.0.1", first.second).use { socket ->
                socket.soTimeout = 5000
                socket.getOutputStream().write(("POST /api/work-orders/$id/materials/submit-request HTTP/1.1\r\nHost: localhost\r\nAuthorization: Bearer ${setup.token}\r\n" +
                    "Idempotency-Key: material-response-loss\r\nContent-Type: application/json\r\nContent-Length: ${payload.toByteArray().size}\r\nConnection: close\r\n\r\n$payload").toByteArray())
                socket.getOutputStream().flush()
            }
            await().atMost(Duration.ofSeconds(30)).until { fixture(setup.token).transaction {
                scalar("SELECT count(*) FROM inventory_material_command WHERE action='SUBMIT'") == "1"
            } }
        } finally { stop(first.first) }
        val original = fixture(setup.token).transaction { scalar("SELECT original_body FROM inventory_material_command WHERE action='SUBMIT'") }
        val second = start("replay")
        try {
            val replay = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build().send(
                HttpRequest.newBuilder(URI("http://127.0.0.1:${second.second}/api/work-orders/$id/materials/submit-request"))
                    .timeout(Duration.ofSeconds(30)).header("Authorization", "Bearer ${setup.token}").header("Idempotency-Key", "material-response-loss")
                    .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(payload)).build(), HttpResponse.BodyHandlers.ofString())
            assertThat(replay.statusCode()).withFailMessage(replay.body()).isEqualTo(200)
            assertThat(replay.body()).isEqualTo(original)
            fixture(setup.token).transaction {
                assertThat(scalar("SELECT count(*) FROM inventory_document WHERE kind='DEMAND'")).isEqualTo("1")
                assertThat(scalar("SELECT count(*) FROM inventory_material_submission")).isEqualTo("1")
            }
        } finally { stop(second.first) }
    }
    @Test fun `revoked warehouse scope denies outer reserve replay after SIGKILL and restored scope replays exactly`() {
        val setup = setupReceipt()
        receiveStock(setup)
        val actor = technician(setup.token)
        val scopePath = "/api/v1/warehouse/settings/scopes/${actor.second}/${setup.bin}"
        assertThat(request("PUT", scopePath, setup.token, """{"expectedRevision":0,"active":true}""").status).isEqualTo(200)
        val id = workOrder(setup.token)
        assign(setup.token, id, actor.second)
        putPlan(setup.token, id, plan(setup.token, id, "[${line(setup.cable, "120000")}]"))
        val payload = command(setup.token, id, 1)
        action(setup.token, id, "submit-request", payload)
        val first = start("reserve-scope")
        val original: String
        try {
            val response = reserveHttp(first.second, id, actor.first, payload)
            assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(200)
            original = response.body()
        } finally { stop(first.first) }
        assertThat(request("PUT", scopePath, setup.token, """{"expectedRevision":1,"active":false}""").status).isEqualTo(200)
        val second = start("revoked-scope")
        try {
            val denied = reserveHttp(second.second, id, actor.first, payload)
            assertThat(denied.statusCode()).withFailMessage(denied.body()).isEqualTo(404)
            assertThat(denied.body()).isNotEqualTo(original)
            assertThat(request("PUT", scopePath, setup.token, """{"expectedRevision":2,"active":true}""").status).isEqualTo(200)
            val restored = reserveHttp(second.second, id, actor.first, payload)
            assertThat(restored.statusCode()).isEqualTo(200)
            assertThat(restored.body()).isEqualTo(original)
            fixture(setup.token).transaction { assertThat(scalar("SELECT count(*) FROM inventory_material_command WHERE action='RESERVE'")).isEqualTo("1") }
        } finally { stop(second.first) }
    }
    private fun reserveHttp(port: Int, id: String, token: String, payload: String) = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build().send(
        HttpRequest.newBuilder(URI("http://127.0.0.1:$port/api/work-orders/$id/materials/reserve"))
            .timeout(Duration.ofSeconds(30)).header("Authorization", "Bearer $token").header("Idempotency-Key", "reserve-restart-scope")
            .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(payload)).build(), HttpResponse.BodyHandlers.ofString())
    private fun start(name: String): Pair<Process, Int> {
        val database = context.getBean(DataSource::class.java).connection.use { it.metaData.url to it.schema }
        val port = temporary.resolve("$name.port")
        val log = temporary.resolve("$name.log")
        val process = ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-Xmx512m", "-cp",
            requireNotNull(System.getProperty("warehouse.test.classpath")), WarehouseReceiptRestartProcess::class.java.name,
            port.toString(), "material-$name", database.first, database.second).redirectErrorStream(true).redirectOutput(log.toFile()).start()
        try {
            return process to com.duluin.ftth.inventory.ChildProcessPort.await(process,port,log)
        } catch (failure: Exception) { stop(process); throw AssertionError("Child startup failed: ${Files.readString(log)}", failure) }
    }
    private fun stop(process: Process) { process.destroyForcibly(); assertThat(process.waitFor(15, TimeUnit.SECONDS)).isTrue() }
}

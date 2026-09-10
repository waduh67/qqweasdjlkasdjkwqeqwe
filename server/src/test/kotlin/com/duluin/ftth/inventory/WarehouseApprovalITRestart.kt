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

@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class WarehouseApprovalITRestart : WarehouseApprovalHttpFixture() {
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
    @Test fun `pending queue survives process replacement and lost final response replays after SIGKILL`() {
        val case = pending()
        val first = start("pending")
        val before: String
        try {
            before = http(first.second, "GET", "/api/v1/warehouse/approvals", case.checker.first).body()
            assertThat(before).contains(case.id, "PENDING")
        } finally { stop(first.first) }
        val second = start("decide")
        try {
            assertThat(http(second.second, "GET", "/api/v1/warehouse/approvals", case.checker.first).body()).isEqualTo(before)
            val payload = decision(case.id)
            Socket("127.0.0.1", second.second).use { socket ->
                socket.soTimeout = 5000
                socket.getOutputStream().write(("POST /api/v1/warehouse/approvals/decide HTTP/1.1\r\nHost: localhost\r\nAuthorization: Bearer ${case.checker.first}\r\n" +
                    "Idempotency-Key: approval-response-loss\r\nContent-Type: application/json\r\nContent-Length: ${payload.toByteArray().size}\r\nConnection: close\r\n\r\n$payload").toByteArray())
                socket.getOutputStream().flush()
            }
            await().atMost(Duration.ofSeconds(30)).until { fixture(case.setup.token).transaction {
                scalar("SELECT count(*) FROM inventory_approval_effect") == "1"
            } }
        } finally { stop(second.first) }
        val third = start("replay")
        try {
            val original = fixture(case.setup.token).transaction { scalar("SELECT original_body FROM inventory_approval_command WHERE namespace='decide'") }
            val replay = http(third.second, "POST", "/api/v1/warehouse/approvals/decide", case.checker.first, decision(case.id), "approval-response-loss")
            assertThat(replay.statusCode()).withFailMessage(replay.body()).isEqualTo(200)
            assertThat(replay.body()).isEqualTo(original)
            assertThat(http(third.second, "POST", "/api/v1/warehouse/approvals/request", case.setup.token, case.source, case.requestKey).body()).isEqualTo(case.original)
            assertThat(http(third.second, "GET", "/api/v1/warehouse/approvals/${case.id}/history", case.checker.first).body()).contains(case.checker.second, "APPROVE")
            assertThat(http(third.second, "POST", "/api/v1/warehouse/approvals/decide", case.setup.token, decision(case.id), "approval-response-loss").statusCode()).isEqualTo(403)
            counts(case, 1, 1)
        } finally { stop(third.first) }
    }
    private fun http(port: Int, method: String, path: String, token: String, body: String? = null, key: String = "read") =
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build().send(HttpRequest.newBuilder(URI("http://127.0.0.1:$port$path"))
            .timeout(Duration.ofSeconds(30)).header("Authorization", "Bearer $token").header("Idempotency-Key", key).header("Content-Type", "application/json")
            .method(method, if (body == null) HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString())
    private fun start(name: String): Pair<Process, Int> {
        val port = temporary.resolve("$name.port")
        val log = temporary.resolve("$name.log")
        val process = ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-Xmx512m", "-cp",
            requireNotNull(System.getProperty("warehouse.test.classpath")), WarehouseReceiptRestartProcess::class.java.name,
            port.toString(), "approval-$name", database.url, database.schema).redirectErrorStream(true).redirectOutput(log.toFile()).start()
        try {
            await().atMost(Duration.ofSeconds(180)).until { check(process.isAlive) { "Child failed: ${Files.readString(log)}" }; Files.exists(port) }
            return process to Files.readString(port).toInt()
        } catch (failure: Exception) { stop(process); throw failure }
    }
    private fun stop(process: Process) { process.destroyForcibly(); assertThat(process.waitFor(15, TimeUnit.SECONDS)).isTrue() }
}

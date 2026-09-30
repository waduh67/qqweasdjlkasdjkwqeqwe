package com.duluin.ftth.inventory

import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.AfterAll
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
import java.time.Instant
import java.util.concurrent.TimeUnit

@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class WarehousePolicyITRestart : WarehousePolicyHttpFixture() {
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
    @Test fun `response loss SIGKILL and restart replay exactly one policy scope and delegation`() {
        val setup = setupReceipt()
        val approver = approver(setup.token, listOf(setup.inspection))
        val delegate = approver(setup.token, listOf(setup.inspection))
        val viewer = user(setup.token, setOf("inventory.location.view"))
        val probe = fixture(setup.token)
        val commands = listOf(
            Triple("PUT", "/api/v1/warehouse/settings/policy", policyBody(listOf(setup.inspection), listOf(approver.second))),
            Triple("PUT", "/api/v1/warehouse/settings/scopes/${viewer.second}/${setup.inspection}", """{"expectedRevision":0,"active":true}"""),
            Triple("POST", "/api/v1/warehouse/settings/delegations", """{"expectedRevision":0,"approverId":"${approver.second}","delegateId":"${delegate.second}",
                "sourceRoleId":null,"locationId":"${setup.inspection}","operation":"RECEIPT","validUntil":"${Instant.now().plusSeconds(1800)}"}""")
        )
        val first = start("first")
        try {
            commands.forEachIndexed { index, (method, path, body) ->
                val key = "policy-restart-$index"
                Socket("127.0.0.1", first.second).use { socket ->
                    socket.soTimeout = 5000
                    socket.getOutputStream().write(("$method $path HTTP/1.1\r\nHost: 127.0.0.1\r\nAuthorization: Bearer ${setup.token}\r\n" +
                        "Idempotency-Key: $key\r\nContent-Type: application/json\r\nContent-Length: ${body.toByteArray().size}\r\nConnection: close\r\n\r\n$body").toByteArray())
                    socket.getOutputStream().flush()
                }
                await().atMost(Duration.ofSeconds(30)).until { probe.transaction {
                    scalar("SELECT count(*) FROM inventory_settings_operation WHERE operation_key='$key'") == "1"
                } }
            }
        } finally { stop(first.first) }
        val restarted = start("second")
        try {
            val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
            commands.forEachIndexed { index, (method, path, body) ->
                val original = probe.transaction { scalar("SELECT original_body FROM inventory_settings_operation WHERE operation_key='policy-restart-$index'") }
                val response = client.send(HttpRequest.newBuilder(URI("http://127.0.0.1:${restarted.second}$path"))
                    .timeout(Duration.ofSeconds(20)).header("Authorization", "Bearer ${setup.token}").header("Idempotency-Key", "policy-restart-$index")
                    .header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString())
                assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(200)
                assertThat(response.body()).isEqualTo(original)
            }
            probe.transaction {
                assertThat(scalar("SELECT count(*) FROM inventory_approval_policy_version")).isEqualTo("1")
                assertThat(scalar("SELECT count(*) FROM inventory_approval_delegation")).isEqualTo("1")
                assertThat(scalar("SELECT count(*) FROM inventory_settings_operation WHERE operation_key LIKE 'policy-restart-%'")).isEqualTo("3")
                for (table in listOf("inventory_approval", "inventory_approval_decision", "inventory_approval_effect", "inventory_movement"))
                    assertThat(scalar("SELECT count(*) FROM $table")).isEqualTo("0")
            }
        } finally { stop(restarted.first) }
    }
    private fun start(name: String): Pair<Process, Int> {
        val port = temporary.resolve("$name.port")
        val log = temporary.resolve("$name.log")
        val process = ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-Xmx512m", "-cp",
            requireNotNull(System.getProperty("warehouse.test.classpath")), WarehouseReceiptRestartProcess::class.java.name,
            port.toString(), "policy-$name", database.url, database.schema).redirectErrorStream(true).redirectOutput(log.toFile()).start()
        try {
            await().atMost(Duration.ofSeconds(180)).until { check(process.isAlive) { "Child failed: ${Files.readString(log)}" }; Files.exists(port) }
            return process to Files.readString(port).toInt()
        } catch (failure: Exception) { stop(process); throw failure }
    }
    private fun stop(process: Process) {
        process.destroyForcibly()
        assertThat(process.waitFor(15, TimeUnit.SECONDS)).isTrue()
    }
}

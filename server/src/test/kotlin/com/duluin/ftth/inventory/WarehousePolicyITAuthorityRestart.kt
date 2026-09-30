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
import java.util.concurrent.TimeUnit

@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class WarehousePolicyITAuthorityRestart : WarehousePolicyVisibilityFixture() {
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
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()

    @Test fun `AV11 role revocation and freshly redacted replay survive discarded HTTP responses and SIGKILL`() {
        val visibility = visibilityScenario(setOf("inventory.receipt.manage", "inventory.approval.view", "inventory.cost.view"))
        val role = userRoles(visibility.setup.token, visibility.operator.second).single()
        val scenario = roleScenario()
        assertThat(request("POST", "/api/v1/warehouse/settings/delegations", scenario.setup.token, delegationBody(scenario)).status).isEqualTo(200)
        val first = start("first")
        try {
            val discarded = client.send(http(first.second, visibility.operator.first, visibility.document), HttpResponse.BodyHandlers.discarding())
            assertThat(discarded.statusCode()).isEqualTo(200)
            val before = send(first.second, scenario.setup.token, scenario.document)
            assertThat(before.statusCode()).isEqualTo(200)
            assertThat(before.body()).contains(scenario.approver.second, scenario.delegate.second)
            changeRole(scenario.setup.token, scenario.configuredRole, setOf("inventory.approval.view"))
            changeRole(visibility.setup.token, role, setOf("inventory.receipt.manage", "inventory.approval.view"))
        } finally { stop(first.first) }
        val restarted = start("second")
        try {
            val deniedRole = send(restarted.second, scenario.setup.token, scenario.document)
            assertThat(deniedRole.statusCode()).isEqualTo(409)
            assertThat(deniedRole.body()).contains("INDEPENDENT_APPROVER_REQUIRED").doesNotContain(scenario.approver.second, scenario.delegate.second)
            assertThat(request("POST", "/api/v1/warehouse/settings/delegations", scenario.setup.token, delegationBody(scenario)).contentAsString)
                .contains("INDEPENDENT_APPROVER_REQUIRED")
            val noCost = send(restarted.second, visibility.operator.first, visibility.document)
            assertThat(noCost.statusCode()).isEqualTo(200)
            assertThat(fields(mapper.readTree(noCost.body()))).isEqualTo(statusFields + setOf("policy", "tiers"))
            assertThat(noCost.body()).doesNotContain(visibility.warehouse2, "valueNumerator", "currency")
            changeRole(visibility.setup.token, role, setOf("inventory.receipt.manage", "inventory.cost.view"))
            val noApproval = send(restarted.second, visibility.operator.first, visibility.document)
            assertThat(noApproval.statusCode()).isEqualTo(200)
            assertThat(fields(mapper.readTree(noApproval.body()))).isEqualTo(statusFields)
            changeRole(visibility.setup.token, role, setOf("inventory.receipt.manage", "inventory.approval.view", "inventory.cost.view"))
            val restored = send(restarted.second, visibility.operator.first, visibility.document)
            assertThat(mapper.readTree(restored.body()).path("valueNumerator").asString()).isEqualTo("101")
            assertThat(client.send(http(restarted.second, visibility.operator.first, visibility.document), HttpResponse.BodyHandlers.discarding()).statusCode()).isEqualTo(200)
            assertThat(request("PUT", "/api/v1/warehouse/settings/scopes/${visibility.operator.second}/${visibility.warehouse1}", visibility.setup.token,
                """{"expectedRevision":1,"active":false}""").status).isEqualTo(200)
            assertThat(send(restarted.second, visibility.operator.first, visibility.document).statusCode()).isEqualTo(404)
            changeRole(scenario.setup.token, scenario.configuredRole, setOf("inventory.approval.view", "inventory.approval.decide"))
            val restoredRole = send(restarted.second, scenario.setup.token, scenario.document)
            assertThat(restoredRole.statusCode()).isEqualTo(200)
            assertThat(restoredRole.body()).contains(scenario.approver.second, scenario.delegate.second)
            assignRoles(scenario.setup.token, scenario.approver.second, listOf(scenario.otherRole))
            assertThat(send(restarted.second, scenario.setup.token, scenario.document).statusCode()).isEqualTo(409)
            for (admin in listOf(scenario.setup.token, visibility.setup.token)) fixture(admin).transaction {
                assertThat(scalar("SELECT count(*) FROM inventory_approval_policy_version")).isEqualTo("1")
                for (table in listOf("inventory_approval", "inventory_approval_decision", "inventory_approval_effect", "inventory_movement"))
                    assertThat(scalar("SELECT count(*) FROM $table")).isEqualTo("0")
            }
        } finally { stop(restarted.first) }
    }
    private fun http(port: Int, token: String, document: String) = HttpRequest.newBuilder(URI("http://127.0.0.1:$port/api/v1/warehouse/settings/evaluate"))
        .timeout(Duration.ofSeconds(20)).header("Authorization", "Bearer $token").header("Idempotency-Key", "lost-evaluation-response")
        .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(sourceBody(document))).build()
    private fun send(port: Int, token: String, document: String) = client.send(http(port, token, document), HttpResponse.BodyHandlers.ofString())
    private fun start(name: String): Pair<Process, Int> {
        val port = temporary.resolve("$name.port")
        val log = temporary.resolve("$name.log")
        val process = ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-Xmx512m", "-cp",
            requireNotNull(System.getProperty("warehouse.test.classpath")), WarehouseReceiptRestartProcess::class.java.name,
            port.toString(), "policy-authority-$name", database.url, database.schema).redirectErrorStream(true).redirectOutput(log.toFile()).start()
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

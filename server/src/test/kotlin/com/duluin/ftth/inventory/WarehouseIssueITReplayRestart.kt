package com.duluin.ftth.inventory

import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
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
import javax.sql.DataSource

@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class WarehouseIssueITReplayRestart : WarehouseIssueReplayFixture() {
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

    @ParameterizedTest @ValueSource(booleans = [false, true])
    fun `immutable substitution and destination replay permissions survive SIGKILL and fresh sessions`(substituted: Boolean) {
        val setup = if (substituted) substitutedSetup() else issuedSetup(serials = 1)
        val picker = picker(setup)
        val destination = transit(setup)
        scope(setup, picker.second, destination, 0, true)
        val path = "/api/work-orders/${setup.workOrder}/materials"
        val pickBody = pickBody(setup)
        val first = start("initial-$substituted")
        val pick: String
        val dispatch: String
        val dispatchBody: String
        val slip: String
        try {
            val picked = http(first.second, "$path/pick", picker.first, pickBody, "restart-pick")
            assertThat(picked.statusCode()).withFailMessage(picked.body()).isEqualTo(200)
            pick = picked.body()
            val issue = mapper.readTree(pick)
            dispatchBody = transitionBody(setup, issue)
            val dispatched = http(first.second, "$path/dispatch", picker.first, dispatchBody, "restart-dispatch")
            assertThat(dispatched.statusCode()).withFailMessage(dispatched.body()).isEqualTo(200)
            dispatch = dispatched.body()
            assertThat(mapper.readTree(dispatch).path("destinations")[0].path("locationId").asString()).isEqualTo(destination)
            slip = "$path/issues/${issue.path("issueId").asString()}/slip"
        } finally { stop(first.first) }
        overridePermission(setup, picker, false)
        val second = start("revoked-$substituted")
        try {
            val expected = if (substituted) 403 else 200
            assertThat(http(second.second, "$path/pick", picker.first, pickBody, "restart-pick").statusCode()).isEqualTo(expected)
            assertThat(http(second.second, "$path/dispatch", picker.first, dispatchBody, "restart-dispatch").statusCode()).isEqualTo(expected)
            assertThat(http(second.second, slip, picker.first).statusCode()).isEqualTo(expected)
            overridePermission(setup, picker, true)
            assertThat(http(second.second, "$path/dispatch", picker.first, dispatchBody, "restart-dispatch").body()).isEqualTo(dispatch)
            for (location in listOf(destination, setup.stock.bin)) {
                scope(setup, picker.second, location, 1, false)
                assertThat(http(second.second, "$path/dispatch", picker.first, dispatchBody, "restart-dispatch").statusCode()).isEqualTo(404)
                assertThat(http(second.second, slip, picker.first).statusCode()).isEqualTo(404)
                scope(setup, picker.second, location, 2, true)
            }
        } finally { stop(second.first) }
        val third = start("restored-$substituted")
        try {
            val me = mapper.readTree(request("GET", "/api/me", picker.first).contentAsString)
            val email = me.path("email").asString()
            val login = http(third.second, "/api/auth/login", null, mapper.writeValueAsString(mapOf(
                "tenantSlug" to email.substringAfter('@').substringBefore(".test"), "email" to email, "password" to "secret12345")), "login")
            assertThat(login.statusCode()).isEqualTo(200)
            val fresh = mapper.readTree(login.body()).path("accessToken").asString()
            assertThat(http(third.second, "$path/pick", fresh, pickBody, "restart-pick").body()).isEqualTo(pick)
            assertThat(http(third.second, "$path/dispatch", fresh, dispatchBody, "restart-dispatch").body()).isEqualTo(dispatch)
            assertThat(http(third.second, slip, fresh).body()).isEqualTo(dispatch)
            fixture(setup.stock.token).transaction {
                assertThat(scalar("SELECT count(*) FROM inventory_movement WHERE kind='ISSUE'")).isEqualTo("1")
                assertThat(scalar("SELECT count(*) FROM inventory_document WHERE kind='ISSUE' AND state='RECEIVED'")).isEqualTo("0")
            }
        } finally { stop(third.first) }
    }
    private fun http(port: Int, path: String, token: String?, body: String? = null, key: String = "read"): HttpResponse<String> {
        val request = HttpRequest.newBuilder(URI("http://127.0.0.1:$port$path")).timeout(Duration.ofSeconds(30))
        if (token != null) request.header("Authorization", "Bearer $token")
        if (body == null) request.GET() else request.header("Content-Type", "application/json").header("Idempotency-Key", key)
            .POST(HttpRequest.BodyPublishers.ofString(body))
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build().send(request.build(), HttpResponse.BodyHandlers.ofString())
    }
    private fun start(name: String): Pair<Process, Int> {
        val database = context.getBean(DataSource::class.java).connection.use { it.metaData.url to it.schema }
        val port = temporary.resolve("$name.port")
        val log = temporary.resolve("$name.log")
        val process = ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-Xmx512m", "-cp",
            requireNotNull(System.getProperty("warehouse.test.classpath")), WarehouseReceiptRestartProcess::class.java.name,
            port.toString(), name, database.first, database.second).redirectErrorStream(true).redirectOutput(log.toFile()).start()
        try {
            await().atMost(Duration.ofSeconds(180)).until { check(process.isAlive) { Files.readString(log) }; Files.exists(port) }
            return process to Files.readString(port).toInt()
        } catch (failure: Exception) { stop(process); throw AssertionError(Files.readString(log), failure) }
    }
    private fun stop(process: Process) { process.destroyForcibly(); assertThat(process.waitFor(15, TimeUnit.SECONDS)).isTrue() }
}

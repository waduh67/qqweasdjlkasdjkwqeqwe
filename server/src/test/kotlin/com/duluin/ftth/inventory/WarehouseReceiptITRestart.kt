package com.duluin.ftth.inventory

import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.Socket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.UUID
import java.util.concurrent.TimeUnit

class WarehouseReceiptITRestart : WarehouseReceiptHttpFixture() {
    @TempDir lateinit var temporary: Path

    @Test fun `response loss followed by SIGKILL restart returns original HTTP receipt without another stock increase`() {
        val setup = setupReceipt()
        val draft = draft(setup, """{"skuId":"${setup.onu}","quantityBase":"1","serials":[{"serial":"PROCESS-REPLAY"}]}""")
        val id = draft.path("id").asString()
        val database = fixture(setup.token)
        val first = start("first")
        try {
            sendWithoutResponse(first.second, setup.token, id, "lost-response")
            await().atMost(Duration.ofSeconds(30)).until { database.transaction {
                scalar("SELECT count(*) FROM inventory_operation WHERE namespace='warehouse.receipt.receive' AND operation_key='lost-response'") == "1"
            } }
        } finally { stop(first.first) }
        val original = database.transaction { scalar("SELECT original_body FROM inventory_operation WHERE namespace='warehouse.receipt.receive' AND operation_key='lost-response'") }
        val restarted = start("second")
        try {
            val response = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build().send(
                HttpRequest.newBuilder(URI("http://127.0.0.1:${restarted.second}/api/v1/warehouse/receipts/$id/receive"))
                    .timeout(Duration.ofSeconds(20)).header("Authorization", "Bearer ${setup.token}")
                    .header("Idempotency-Key", "lost-response").header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("""{"expectedRevision":0}""")).build(), HttpResponse.BodyHandlers.ofString())
            assertThat(response.statusCode()).isEqualTo(200)
            assertThat(response.body()).isEqualTo(original)
            database.transaction {
                assertThat(scalar("SELECT count(*) FROM inventory_movement")).isEqualTo("1")
                assertThat(scalar("SELECT sum(quantity_base) FROM inventory_balance_projection")).isEqualTo("1")
                assertThat(scalar("SELECT count(*) FROM inventory_identity_claim")).isEqualTo("1")
            }
        } finally { stop(restarted.first) }
    }

    @Test fun `terminated transaction during receive leaves no identity operation or stock before fresh retry`() {
        val setup = setupReceipt()
        val draft = draft(setup, """{"skuId":"${setup.onu}","quantityBase":"1","serials":[{"serial":"INTERRUPTED-RECEIVE"}]}""")
        val id = draft.path("id").asString()
        val database = fixture(setup.token)
        val owner = context.getBean(org.flywaydb.core.Flyway::class.java).configuration.dataSource
        val marker = "receipt_pause_${UUID.randomUUID().toString().replace("-", "")}"
        val lockKey = UUID.randomUUID().leastSignificantBits
        owner.connection.use { lock ->
            lock.createStatement().use { statement ->
                statement.execute("SELECT pg_advisory_lock($lockKey)")
                statement.execute("CREATE FUNCTION $marker() RETURNS trigger LANGUAGE plpgsql AS 'BEGIN PERFORM pg_advisory_xact_lock($lockKey); RETURN NEW; END'")
                statement.execute("CREATE TRIGGER $marker BEFORE INSERT ON inventory_movement FOR EACH ROW WHEN (NEW.document_id='$id') EXECUTE FUNCTION $marker()")
            }
            var child: Process? = null
            try {
                val process = start(marker)
                child = process.first
                sendWithoutResponse(process.second, setup.token, id, "interrupted")
                context.getBean(javax.sql.DataSource::class.java).connection.use { observer ->
                    await().atMost(Duration.ofSeconds(30)).until { observer.createStatement().use { statement ->
                        statement.executeQuery("SELECT count(*) FROM pg_stat_activity WHERE application_name='$marker' AND wait_event='advisory'").use { rows -> rows.next(); rows.getInt(1) == 1 }
                    } }
                    observer.createStatement().use { statement ->
                        statement.execute("SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE application_name='$marker' AND wait_event='advisory'")
                    }
                }
            } finally {
                child?.let(::stop)
                lock.createStatement().use { statement ->
                    statement.execute("SELECT pg_advisory_unlock($lockKey)")
                    statement.execute("DROP TRIGGER $marker ON inventory_movement")
                    statement.execute("DROP FUNCTION $marker()")
                }
            }
        }
        database.transaction {
            for (table in listOf("inventory_operation", "inventory_movement", "inventory_identity_claim", "inventory_segment", "inventory_balance_projection")) {
                val predicate = if (table == "inventory_operation") " WHERE namespace='warehouse.receipt.receive'" else ""
                assertThat(scalar("SELECT count(*) FROM $table$predicate")).isEqualTo("0")
            }
        }
        transition(setup, id, "receive", """{"expectedRevision":0}""", "interrupted")
        database.transaction { assertThat(scalar("SELECT count(*) FROM inventory_movement")).isEqualTo("1") }
    }

    private fun start(name: String): Pair<Process, Int> {
        val port = temporary.resolve("$name.port")
        val classpath = requireNotNull(System.getProperty("warehouse.test.classpath"))
        val process = ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-Xmx512m", "-cp", classpath,
            WarehouseReceiptRestartProcess::class.java.name, port.toString(), name)
            .redirectErrorStream(true).redirectOutput(temporary.resolve("$name.log").toFile()).start()
        try {
            await().atMost(Duration.ofSeconds(100)).until { check(process.isAlive) { "Child server failed: ${Files.readString(temporary.resolve("$name.log"))}" }; Files.exists(port) }
            return process to Files.readString(port).toInt()
        } catch (failure: Exception) { stop(process); throw failure }
    }
    private fun stop(process: Process) {
        process.destroyForcibly()
        assertThat(process.waitFor(15, TimeUnit.SECONDS)).isTrue()
    }
    private fun sendWithoutResponse(port: Int, token: String, id: String, key: String) {
        val body = """{"expectedRevision":0}"""
        Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 5000
            socket.getOutputStream().write(("POST /api/v1/warehouse/receipts/$id/receive HTTP/1.1\r\nHost: 127.0.0.1\r\n" +
                "Authorization: Bearer $token\r\nIdempotency-Key: $key\r\nContent-Type: application/json\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n$body").toByteArray())
            socket.getOutputStream().flush()
        }
    }
}

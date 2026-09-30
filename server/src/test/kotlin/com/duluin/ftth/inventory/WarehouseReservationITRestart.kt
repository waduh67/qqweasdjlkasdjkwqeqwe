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
class WarehouseReservationITRestart : WarehouseReservationFixture() {
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

    @Test fun `committed response loss and allocation revisions survive killed server and restarted expiry workers`() {
        val (token, fixture) = prepare()
        receive(fixture, "60")
        val demand = demand(token, fixture, 100000, false)
        val body = """{"expectedRevision":1,"workOrderRevision":0,"planRevision":1}"""
        val first = start("first")
        val result: String
        val snapshot: String
        try {
            result = http(first.second, token, "${demand.document}/reserve", body)
            snapshot = http(first.second, token, "allocations/${demand.workOrder}")
        } finally { stop(first.first) }
        fixture.transaction {
            val newer = java.util.UUID.randomUUID()
            sql("""INSERT INTO inventory_material_plan(id,tenant_id,work_order_id,plan_revision,work_order_revision,material_mode,actor_id)
                SELECT '$newer',tenant_id,work_order_id,2,work_order_revision,material_mode,actor_id FROM inventory_material_plan WHERE work_order_id='${demand.workOrder}' AND plan_revision=1""")
            sql("""INSERT INTO inventory_material_plan_line(id,tenant_id,plan_id,line_number,sku_id,quantity_base,base_unit,continuous_cut)
                SELECT gen_random_uuid(),tenant_id,'$newer',line_number,sku_id,quantity_base,base_unit,continuous_cut FROM inventory_material_plan_line
                WHERE plan_id=(SELECT id FROM inventory_material_plan WHERE work_order_id='${demand.workOrder}' AND plan_revision=1)""")
        }
        val second = start("second")
        try {
            assertThat(http(second.second, token, "${demand.document}/reserve", body)).isEqualTo(result)
            assertThat(http(second.second, token, "allocations/${demand.workOrder}")).isEqualTo(snapshot)
        } finally { stop(second.first) }
        val legs = fixture.transaction { scalar("SELECT count(*) FROM inventory_movement_leg") }
        fixture.transaction { sql("UPDATE inventory_reservation SET submitted_at=clock_timestamp()-interval '2 days',expires_at=clock_timestamp()-interval '1 second',revision=revision+1") }
        expire("expire-first", fixture.tenant.toString(), "true")
        expire("expire-restarted", fixture.tenant.toString(), "false")
        assertThat(fixture.transaction { scalar("SELECT count(*) FROM inventory_outbox WHERE event_kind='RESERVATION_EXPIRED'") }).isEqualTo("1")
        assertThat(fixture.transaction { scalar("SELECT count(*) FROM inventory_movement_leg") }).isEqualTo(legs)
        val row = allocations(demand).single()
        assertThat(row.path("state").asString()).isEqualTo("EXPIRED")
        assertThat(row.path("reservedUnpickedBase").asString()).isEqualTo("0")
        assertThat(row.path("reservationRevision").asLong()).isEqualTo(2)
        assertThat(row.path("demandSupply").path("requestedBase").asString()).isEqualTo("100000")
        assertThat(row.path("demandSupply").path("backorderBase").asString()).isEqualTo("100000")
        assertThat(row.path("demandSupply").path("demandState").asString()).isEqualTo("SUBMITTED")
    }
    private fun http(port: Int, token: String, path: String, body: String? = null): String {
        val builder = HttpRequest.newBuilder(URI("http://127.0.0.1:$port/api/v1/warehouse/material-requests/$path"))
            .timeout(Duration.ofSeconds(30)).header("Authorization", "Bearer $token")
        if (body == null) builder.GET() else builder.header("Idempotency-Key", "lost-response").header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body))
        val response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
        assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(200)
        return response.body()
    }
    private fun process(name: String, type: Class<*>, vararg args: String) = ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
        "-Xmx512m", "-cp", requireNotNull(System.getProperty("warehouse.test.classpath")), type.name, *args)
        .redirectErrorStream(true).redirectOutput(temporary.resolve("$name.log").toFile()).start()
    private fun start(name: String): Pair<Process, Int> {
        val port = temporary.resolve("$name.port")
        val process = process(name, WarehouseReceiptRestartProcess::class.java, port.toString(), name, database.url, database.schema)
        try {
            await().atMost(Duration.ofSeconds(140)).until { check(process.isAlive) { Files.readString(temporary.resolve("$name.log")) }; Files.exists(port) }
            return process to Files.readString(port).toInt()
        } catch (failure: Exception) { stop(process); throw failure }
    }
    private fun expire(name: String, tenant: String, expected: String) {
        val result = temporary.resolve("$name.result")
        val process = process(name, WarehouseReservationExpiryProcess::class.java, result.toString(), database.url, database.schema, tenant)
        try {
            assertThat(process.waitFor(150, TimeUnit.SECONDS)).isTrue()
            assertThat(process.exitValue()).withFailMessage(Files.readString(temporary.resolve("$name.log"))).isZero()
            assertThat(Files.readString(result)).isEqualTo(expected)
        } finally { if (process.isAlive) stop(process) }
    }
    private fun stop(process: Process) { process.destroyForcibly(); assertThat(process.waitFor(15, TimeUnit.SECONDS)).isTrue() }
}

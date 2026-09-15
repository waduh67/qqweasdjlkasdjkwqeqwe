package com.duluin.ftth.customer

import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.fulfillment.AssetProvisioningDelivery
import com.duluin.ftth.fulfillment.AssetProvisioningDeliveryStore
import com.duluin.ftth.provisioning.AssetProvisioningHttpAdapter
import com.sun.net.httpserver.HttpServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

abstract class CustomerAssetReplacementScenarios : CustomerAssetReplacementFixture() {
    @Test
    fun `external adapter failure and redelivery leave committed physical and episode facts unchanged`() {
        val swap = swappedCase()
        val stock = fixture(swap.case.replacement.stock.token)
        val before = physicalFingerprint(swap.case.old)
        val telemetry = telemetryFingerprint(swap.case.old)
        val status = AtomicInteger(503)
        val calls = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/apply") { exchange ->
            calls.incrementAndGet()
            val request = mapper.readTree(exchange.requestBody.readAllBytes())
            val reply = mapper.writeValueAsBytes(mapOf("operationId" to request.path("operationId").asString(), "state" to "SUCCEEDED"))
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(status.get(), reply.size.toLong())
            exchange.responseBody.use { it.write(reply) }
            exchange.close()
        }
        server.start()
        val store = context.getBean(AssetProvisioningDeliveryStore::class.java)
        val adapter = AssetProvisioningHttpAdapter("http://127.0.0.1:${server.address.port}/apply", "")
        try {
            val failedDelivery = TenantContext.runAs(stock.tenant) { AssetProvisioningDelivery(store, adapter).deliver(swap.operation) }

            assertThat(failedDelivery).isTrue()
            assertThat(physicalFingerprint(swap.case.old)).isEqualTo(before)
            assertThat(telemetryFingerprint(swap.case.old)).isEqualTo(telemetry)
            stock.transaction {
                assertThat(scalar("SELECT state||'|'||failure_code FROM fulfillment_asset_delivery WHERE operation_id='${swap.operation}'"))
                    .isEqualTo("RECONCILIATION_REQUIRED|ASSET_ADAPTER_HTTP_503")
                assertThat(scalar("SELECT count(*) FROM onu WHERE customer_id='${swap.case.old.installation.customer}'")).isEqualTo("2")
            }
            status.set(200)
            val restartedDelivery = AssetProvisioningDelivery(store, AssetProvisioningHttpAdapter("http://127.0.0.1:${server.address.port}/apply", ""))
            TenantContext.runAs(stock.tenant) { assertThat(restartedDelivery.deliver(swap.operation)).isTrue() }
            assertThat(physicalFingerprint(swap.case.old)).isEqualTo(before)
            assertThat(telemetryFingerprint(swap.case.old)).isEqualTo(telemetry)
            stock.transaction { assertThat(scalar("SELECT state||'|'||attempts FROM fulfillment_asset_delivery WHERE operation_id='${swap.operation}'")).isEqualTo("SUCCEEDED|2") }
            TenantContext.runAs(stock.tenant) { assertThat(restartedDelivery.deliver(swap.operation)).isFalse() }
            assertThat(calls.get()).isEqualTo(2)
        } finally { server.stop(0) }
    }

    @Test
    fun `exact swap replay returns original response and new key cannot remove the old asset twice`() {
        val swap = swappedCase()
        val before = physicalFingerprint(swap.case.old)
        val path = "/api/customers/${swap.case.old.installation.customer}/assets/replace"

        val replay = request("POST", path, swap.case.replacement.receiver.first, swap.request, "swap")

        assertThat(replay.status).withFailMessage(replay.contentAsString).isEqualTo(201)
        assertThat(replay.contentAsString).isEqualTo(swap.body)
        assertThat(physicalFingerprint(swap.case.old)).isEqualTo(before)
        assertThat(request("POST", path, swap.case.replacement.receiver.first, swap.request, "second-swap").status).isEqualTo(409)
    }

    @Test
    fun `revoked original actor cannot retrieve private swap outcome`() {
        val swap = swappedCase()
        assertThat(request("PUT", "/api/users/${swap.case.replacement.receiver.second}/access", swap.case.replacement.stock.token,
            """{"roleIds":[],"areaIds":[]}""").status).isEqualTo(200)
        val before = physicalFingerprint(swap.case.old)

        val replay = request("POST", "/api/customers/${swap.case.old.installation.customer}/assets/replace", swap.case.replacement.receiver.first, swap.request, "swap")

        assertThat(replay.status).isEqualTo(403)
        assertThat(physicalFingerprint(swap.case.old)).isEqualTo(before)
    }

    @Test
    fun `simultaneous competing swaps commit exactly one recovery and one replacement`() {
        val case = replacementCase()
        val authorized = authorizeReplacement(case)
        assertThat(authorized.status).isEqualTo(200)
        val authorization = mapper.readTree(authorized.contentAsString).path("authorizationId").asString()
        val body = """{"authorizationId":"$authorization","expectedRevision":0,"expectedAssignmentRevision":1,
            "expectedTitleRevision":0,"evidenceId":"${case.evidence}","topology":null}"""
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val futures = (1..2).map { contender -> pool.submit<Int> {
                ready.countDown()
                check(start.await(15, TimeUnit.SECONDS))
                request("POST", "/api/customers/${case.old.installation.customer}/assets/replace", case.replacement.receiver.first, body, "race-$contender").status
            } }
            check(ready.await(15, TimeUnit.SECONDS))

            start.countDown()
            val results = futures.map { it.get(45, TimeUnit.SECONDS) }

            assertThat(results).containsExactlyInAnyOrder(201, 409)
            fixture(case.replacement.stock.token).transaction {
                assertThat(scalar("SELECT count(*) FROM inventory_asset_removal WHERE assignment_id='${case.old.installation.operation}'")).isEqualTo("1")
                assertThat(scalar("SELECT count(*) FROM inventory_asset_assignment WHERE customer_id='${case.old.installation.customer}' AND ended_at IS NULL")).isEqualTo("1")
            }
        } finally { pool.shutdownNow() }
    }
}

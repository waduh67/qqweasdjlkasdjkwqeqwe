package com.duluin.ftth.monitoring

import com.duluin.ftth.contract.MetricBatch
import com.duluin.ftth.contract.OnuOperationalStatus
import com.duluin.ftth.contract.OnuReading
import com.duluin.ftth.customer.CustomerAssetReplacementFixture
import com.duluin.ftth.customer.CustomerObservationApi
import com.duluin.ftth.inventory.adapter.outbound.persistence.AssetRemovalStore
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.http.MediaType
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class WarehouseDiscoveryITReviewLockOrder : CustomerAssetReplacementFixture() {
    @MockitoSpyBean private lateinit var observations: CustomerObservationApi
    @MockitoSpyBean private lateinit var removals: AssetRemovalStore

    @Test
    fun `T6 opposite ONU and asset locks yield retryable HTTP with no partial posting`() {
        val old = ownershipCase("LOAN")
        assertThat(accept(old).status).isEqualTo(200)
        val receipt = old.installation.receipt
        val stock = fixture(receipt.stock.token)
        val order = workOrder(receipt.stock.token, "DISMANTLE", old.installation.customer.toString())
        assign(receipt.stock.token, order, receipt.receiver.second)
        val evidence = removalEvidence(receipt.receiver.first, order)
        val registration = request("POST", "/api/monitoring/collectors", receipt.stock.token, """{"name":"Lock order","pollIntervalSeconds":60}""")
        assertThat(registration.status).isEqualTo(201)
        val key = mapper.readTree(registration.contentAsString).path("apiKey").asString()
        val serial = requireNotNull(receipt.input.lines.single().serial)
        val now = Instant.now()
        val body = mapper.writeValueAsString(MetricBatch(UUID.randomUUID().toString(), now,
            listOf(OnuReading(serial, "OLT-X", null, OnuOperationalStatus.LOS, -30.0, null, null, null, now))))
        fun ingest(): Int = mvc.perform(post("/api/collector/metrics").header("X-Collector-Key", key)
            .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn().response.status
        val removeBody = """{"assignmentId":"${old.installation.operation}","expectedRevision":1,"expectedTitleRevision":0,"workOrderId":"$order","evidenceId":"$evidence"}"""
        fun remove(): Int = request("POST", "/api/customers/${old.installation.customer}/assets/remove", receipt.receiver.first, removeBody, "lock-remove").status
        val onuHeld = CountDownLatch(1)
        val assetHeld = CountDownLatch(1)
        val ingestPid = AtomicInteger()
        stock.transaction {
            Mockito.doAnswer { invocation ->
                val result = invocation.callRealMethod()
                ingestPid.set(stock.transaction { scalar("SELECT pg_backend_pid()").toInt() })
                onuHeld.countDown(); check(assetHeld.await(20, TimeUnit.SECONDS))
                result
            }.`when`(observations).lockEpisodes(setOf(serial))
        }
        Mockito.doAnswer { invocation ->
            val result = invocation.callRealMethod()
            assetHeld.countDown()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            var waiting = false
            while (!waiting && System.nanoTime() < deadline) {
                waiting = stock.transaction { scalar("SELECT count(*) FROM pg_locks WHERE pid=${ingestPid.get()} AND NOT granted").toInt() > 0 }
                if (!waiting) Thread.sleep(10)
            }
            check(waiting)
            result
        }.`when`(removals).lock(old.installation.operation, null)
        try {
            Executors.newFixedThreadPool(2).use { executor ->
                val ingested = executor.submit<Int> { ingest() }
                check(onuHeld.await(20, TimeUnit.SECONDS))
                val removed = executor.submit<Int> { remove() }
                assertThat(listOf(ingested.get(40, TimeUnit.SECONDS), removed.get(40, TimeUnit.SECONDS))).containsExactlyInAnyOrder(200, 409)
            }
        } finally {
            assetHeld.countDown()
            Mockito.reset(observations, removals)
        }
        assertThat(ingest()).isEqualTo(200)
        assertThat(remove()).isEqualTo(200)
        stock.transaction {
            assertThat(scalar("SELECT count(*) FROM onu_metric WHERE onu_id='${old.installation.operation}'")).isEqualTo("1")
            assertThat(scalar("SELECT count(*) FROM ingest_batch")).isEqualTo("1")
            assertThat(scalar("SELECT count(*) FROM inventory_asset_removal WHERE assignment_id='${old.installation.operation}'")).isEqualTo("1")
            assertThat(scalar("SELECT status FROM onu WHERE id='${old.installation.operation}'")).isEqualTo("DISMANTLED")
        }
    }
}

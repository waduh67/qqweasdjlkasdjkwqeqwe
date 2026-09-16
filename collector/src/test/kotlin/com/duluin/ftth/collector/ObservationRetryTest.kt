package com.duluin.ftth.collector

import com.duluin.ftth.contract.*
import com.duluin.ftth.snmp.AdapterRegistry
import com.duluin.ftth.snmp.OltAdapter
import com.duluin.ftth.snmp.ProbeResult
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ObservationRetryTest {
    @Test
    fun `collector retries observation conflict using the original batch identity`() {
        val target = OltTarget("owned", "OWNED", "TEST", "127.0.0.1", snmpCommunity = "owned")
        val attempted = mutableListOf<MetricBatch>()
        val client = object : ServerClient {
            override fun heartbeat(heartbeat: CollectorHeartbeat) = CollectorConfig("owned", 60, listOf(target))
            override fun pushMetrics(batch: MetricBatch): IngestResult {
                attempted += batch
                if (attempted.size == 1) throw ServerRejectedException(409, "OBSERVATION_RETRY")
                return IngestResult(batch.readings.size, emptyList())
            }
            override fun pushBngSessions(batch: BngSessionBatch) = BngIngestResult(0)
        }
        val adapter = object : OltAdapter {
            override val vendor = "TEST"
            override fun probe(target: OltTarget) = ProbeResult.Reachable("owned", 1)
            override fun pollOnus(target: OltTarget) = listOf(OnuReading("OWNED", target.oltCode, null,
                OnuOperationalStatus.ONLINE, null, null, null, null, Instant.now()))
        }
        CollectorAgent(client, AdapterRegistry(listOf(adapter)), "test", sleeper = {}).runOnce()
        assertEquals(2, attempted.size)
        assertEquals(1, attempted.map { it.batchId }.distinct().size)
        assertTrue(ServerRejectedException(401, "invalid key").permanent)
    }
}

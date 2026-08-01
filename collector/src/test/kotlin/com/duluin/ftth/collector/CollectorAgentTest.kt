package com.duluin.ftth.collector

import com.duluin.ftth.collector.adapter.BngAdapterRegistry
import com.duluin.ftth.collector.adapter.SimulatorBngAdapter
import com.duluin.ftth.snmp.AdapterRegistry
import com.duluin.ftth.contract.BngActionCommand
import com.duluin.ftth.contract.BngActionKind
import com.duluin.ftth.contract.BngIngestResult
import com.duluin.ftth.contract.BngSessionBatch
import com.duluin.ftth.contract.CollectorConfig
import com.duluin.ftth.contract.CollectorHeartbeat
import com.duluin.ftth.contract.IngestResult
import com.duluin.ftth.contract.MetricBatch
import com.duluin.ftth.contract.NasTarget
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Menguji jalur turun perintah BRAS di [CollectorAgent] (Phase 7c): perintah yang
 * datang lewat konfigurasi denyut dieksekusi pada siklus itu, dan hasilnya dititipkan
 * untuk di-ACK pada denyut BERIKUTNYA — lalu dikosongkan setelah denyut itu sukses.
 *
 * Yang paling gampang salah dan karena itu ditegakkan di sini:
 * - Perintah untuk BRAS yang tak ada di konfigurasi tetap menghasilkan ACK gagal
 *   (bukan menggantung diam-diam sehingga server mengirim ulang selamanya).
 * - ACK menumpang denyut berikutnya, bukan denyut yang membawanya (arah tetap outbound).
 * - Antrean ACK dikosongkan setelah terkirim, jadi tidak dikirim dua kali.
 */
class CollectorAgentTest {

    /** ServerClient tiruan: memutar konfigurasi terprogram dan merekam tiap denyut. */
    private class FakeServerClient(private val configs: ArrayDeque<CollectorConfig>) : ServerClient {
        val heartbeats = mutableListOf<CollectorHeartbeat>()

        override fun heartbeat(heartbeat: CollectorHeartbeat): CollectorConfig {
            heartbeats += heartbeat
            return configs.removeFirstOrNull() ?: IDLE
        }

        override fun pushMetrics(batch: MetricBatch): IngestResult =
            IngestResult(accepted = batch.readings.size, unknownSerialNumbers = emptyList())

        override fun pushBngSessions(batch: BngSessionBatch): BngIngestResult =
            BngIngestResult(accepted = batch.sessions.size)

        companion object {
            val IDLE = CollectorConfig(collectorName = "sim", pollIntervalSeconds = 60, targets = emptyList())
        }
    }

    private val nasTarget = NasTarget(
        nasId = "nas-1",
        name = "BRAS-SIM",
        vendor = "MIKROTIK",
        host = "10.9.9.9",
        adapterType = "SIMULATOR",
        // Kosong: fokus tes ini perintah turun, bukan polling sesi.
        expectedUsernames = emptyList(),
    )

    private fun agentWith(configs: ArrayDeque<CollectorConfig>): Pair<CollectorAgent, FakeServerClient> {
        val client = FakeServerClient(configs)
        val agent = CollectorAgent(
            client = client,
            registry = AdapterRegistry(emptyList()),
            agentVersion = "test-1.0",
            sleeper = {},
            clock = { Instant.parse("2026-07-26T00:00:00Z") },
            // Simulator memerankan BRAS vendor apa pun lewat fallback.
            bngRegistry = BngAdapterRegistry(emptyList(), fallback = SimulatorBngAdapter()),
        )
        return agent to client
    }

    @Test
    fun `perintah dieksekusi lalu hasilnya menumpang denyut berikutnya sekali`() {
        val withActions = CollectorConfig(
            collectorName = "sim",
            pollIntervalSeconds = 60,
            targets = emptyList(),
            nasTargets = listOf(nasTarget),
            bngActions = listOf(
                BngActionCommand("a1", "nas-1", BngActionKind.DISCONNECT, "budi@isp"),
                BngActionCommand("a2", "nas-1", BngActionKind.COA, "budi@isp", downMbps = 50, upMbps = 20),
            ),
        )
        val (agent, client) = agentWith(ArrayDeque(listOf(withActions)))

        // Denyut 1: menerima perintah (denyut ini belum membawa hasil apa pun),
        // lalu mengeksekusinya di siklus.
        agent.runOnce()
        // Denyut 2: konfigurasi idle, namun denyut ini MEMBAWA hasil eksekusi denyut 1.
        agent.runOnce()
        // Denyut 3: antrean ACK sudah kosong — hasil tak dikirim dua kali.
        agent.runOnce()

        assertEquals(3, client.heartbeats.size)
        assertTrue(client.heartbeats[0].actionResults.isEmpty(), "denyut pembawa perintah belum boleh punya ACK")

        val acks = client.heartbeats[1].actionResults.associateBy { it.actionId }
        assertEquals(setOf("a1", "a2"), acks.keys, "kedua perintah harus di-ACK di denyut berikutnya")
        assertTrue(acks.getValue("a1").success, "DISCONNECT simulator selalu sukses")
        assertTrue(acks.getValue("a2").success, "CoA simulator selalu sukses")

        assertTrue(client.heartbeats[2].actionResults.isEmpty(), "ACK tak boleh terkirim dua kali")
    }

    @Test
    fun `perintah untuk BRAS di luar konfigurasi di-ACK gagal, bukan digantung`() {
        val withOrphanAction = CollectorConfig(
            collectorName = "sim",
            pollIntervalSeconds = 60,
            targets = emptyList(),
            // Sengaja tanpa nasTargets: BRAS "nas-hantu" tak dikenal collector ini.
            nasTargets = emptyList(),
            bngActions = listOf(BngActionCommand("z9", "nas-hantu", BngActionKind.DISCONNECT, "siapa@isp")),
        )
        val (agent, client) = agentWith(ArrayDeque(listOf(withOrphanAction)))

        agent.runOnce()
        agent.runOnce()

        val ack = client.heartbeats[1].actionResults.single()
        assertEquals("z9", ack.actionId)
        assertTrue(!ack.success, "perintah tanpa BRAS yang cocok harus ACK gagal")
        assertTrue(ack.detail?.contains("nas-hantu") == true, "detail harus menerangkan BRAS yang hilang")
    }
}

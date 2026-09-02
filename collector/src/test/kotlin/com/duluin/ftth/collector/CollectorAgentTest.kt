package com.duluin.ftth.collector

import com.duluin.ftth.collector.adapter.BngAdapterRegistry
import com.duluin.ftth.collector.adapter.ProvisioningAdapter
import com.duluin.ftth.collector.adapter.ProvisioningAdapterRegistry
import com.duluin.ftth.collector.adapter.SimulatorBngAdapter
import com.duluin.ftth.snmp.AdapterRegistry
import com.duluin.ftth.contract.BngActionCommand
import com.duluin.ftth.contract.BngActionKind
import com.duluin.ftth.contract.BngIngestResult
import com.duluin.ftth.contract.BngSessionBatch
import com.duluin.ftth.contract.CollectorConfig
import com.duluin.ftth.contract.CollectorHeartbeat
import com.duluin.ftth.contract.DeviceCapabilityReport
import com.duluin.ftth.contract.DeviceFingerprint
import com.duluin.ftth.contract.IngestResult
import com.duluin.ftth.contract.MetricBatch
import com.duluin.ftth.contract.NasTarget
import com.duluin.ftth.contract.ProvisioningApplyResult
import com.duluin.ftth.contract.ProvisioningCommandPhase
import com.duluin.ftth.contract.ProvisioningErrorCode
import com.duluin.ftth.contract.ProvisioningPayload
import com.duluin.ftth.contract.ProvisioningPlanStepCommand
import com.duluin.ftth.contract.ProvisioningStepResult
import com.duluin.ftth.contract.ProvisioningTarget
import com.duluin.ftth.contract.ProvisioningVerificationObservation
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
        var onHeartbeat: (() -> Unit)? = null

        override fun heartbeat(heartbeat: CollectorHeartbeat): CollectorConfig {
            heartbeats += heartbeat
            onHeartbeat?.invoke()
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
    fun `recorded provisioning results ride the next successful heartbeat once`() {
        val (agent, client) = agentWith(ArrayDeque())
        val result = ProvisioningStepResult(
            planId = "plan-1",
            revision = 1,
            stepId = "step-1",
            operationClass = "ENSURE_TAGGED_VLAN",
            idempotencyKey = "plan-1:1:step-1",
            success = false,
            completedAt = Instant.parse("2026-07-26T00:00:00Z"),
            errorCode = ProvisioningErrorCode.UNSUPPORTED_CAPABILITY,
        )

        agent.recordProvisioningResult(result)
        agent.runOnce()
        agent.runOnce()

        assertEquals(listOf(result), client.heartbeats[0].provisioningResults)
        assertTrue(client.heartbeats[1].provisioningResults.isEmpty())
    }

    @Test
    fun `provisioning commands are stored for a future adapter without execution`() {
        val command = ProvisioningPlanStepCommand(
            planId = "plan-1",
            revision = 1,
            stepId = "step-1",
            phase = ProvisioningCommandPhase.PREFLIGHT,
            operationClass = "ENSURE_TAGGED_VLAN",
            idempotencyKey = "plan-1:1:step-1:preflight",
            deadline = Instant.parse("2026-07-26T00:05:00Z"),
            target = ProvisioningTarget("switch-1", "SWITCH", "10.0.0.2", "SSH"),
            payload = ProvisioningPayload(mapOf("vlanId" to "110")),
        )
        val config = FakeServerClient.IDLE.copy(provisioningCommands = listOf(command))
        val (agent, _) = agentWith(ArrayDeque(listOf(config)))

        agent.runOnce()

        assertEquals(listOf(command), agent.takeProvisioningCommands())
        assertTrue(agent.takeProvisioningCommands().isEmpty(), "commands are claimed once")
    }

    @Test
    fun `result recorded during heartbeat is retained for the following heartbeat`() {
        val (agent, client) = agentWith(ArrayDeque())
        val first = failedProvisioningResult("step-1")
        val late = failedProvisioningResult("step-2")
        agent.recordProvisioningResult(first)
        client.onHeartbeat = {
            client.onHeartbeat = null
            agent.recordProvisioningResult(late)
        }

        agent.runOnce()
        agent.runOnce()

        assertEquals(listOf(first), client.heartbeats[0].provisioningResults)
        assertEquals(listOf(late), client.heartbeats[1].provisioningResults)
    }

    @Test
    fun `unclaimed provisioning commands survive later config batches`() {
        val first = provisioningCommand("step-1")
        val second = provisioningCommand("step-2")
        val configs = ArrayDeque(
            listOf(
                FakeServerClient.IDLE.copy(provisioningCommands = listOf(first)),
                FakeServerClient.IDLE.copy(provisioningCommands = listOf(second)),
            ),
        )
        val (agent, _) = agentWith(configs)

        agent.runOnce()
        agent.runOnce()

        assertEquals(listOf(first, second), agent.takeProvisioningCommands())
    }

    @Test
    fun `registered provisioning adapter executes and reports on next heartbeat`() {
        val command = provisioningCommand("step-router")
        val target = NasTarget(
            nasId = "switch-1",
            name = "router",
            vendor = "MIKROTIK",
            host = "router.example",
            adapterType = "ROUTER_OS",
        )
        val config = FakeServerClient.IDLE.copy(
            nasTargets = listOf(target),
            provisioningCommands = listOf(command),
        )
        val client = FakeServerClient(ArrayDeque(listOf(config, FakeServerClient.IDLE)))
        val adapter = RecordingProvisioningAdapter()
        val agent = CollectorAgent(
            client = client,
            registry = AdapterRegistry(emptyList()),
            agentVersion = "test-1.0",
            sleeper = {},
            clock = { Instant.parse("2026-07-26T00:00:00Z") },
            provisioningRegistry = ProvisioningAdapterRegistry(listOf(adapter)),
        )

        agent.runOnce()
        agent.runOnce()
        agent.runOnce()

        assertEquals(listOf(command), adapter.commands)
        assertEquals("step-router", client.heartbeats[1].provisioningResults.single().stepId)
        assertEquals("switch-1", client.heartbeats[1].deviceReports.single().targetId)
        assertTrue(client.heartbeats[2].provisioningResults.isEmpty())
        assertTrue(client.heartbeats[2].deviceReports.isEmpty())
    }

    private fun failedProvisioningResult(stepId: String) = ProvisioningStepResult(
        planId = "plan-1",
        revision = 1,
        stepId = stepId,
        operationClass = "ENSURE_TAGGED_VLAN",
        idempotencyKey = "plan-1:1:$stepId",
        success = false,
        completedAt = Instant.parse("2026-07-26T00:00:00Z"),
        errorCode = ProvisioningErrorCode.UNSUPPORTED_CAPABILITY,
    )

    private fun provisioningCommand(stepId: String) = ProvisioningPlanStepCommand(
        planId = "plan-1",
        revision = 1,
        stepId = stepId,
        phase = ProvisioningCommandPhase.PREFLIGHT,
        operationClass = "ENSURE_TAGGED_VLAN",
        idempotencyKey = "plan-1:1:$stepId:preflight",
        deadline = Instant.parse("2026-07-26T00:05:00Z"),
        target = ProvisioningTarget("switch-1", "SWITCH", "10.0.0.2", "SSH"),
    )

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

    private class RecordingProvisioningAdapter : ProvisioningAdapter {
        override val vendor = "MIKROTIK"
        val commands = mutableListOf<ProvisioningPlanStepCommand>()

        override fun execute(target: NasTarget, command: ProvisioningPlanStepCommand): ProvisioningStepResult {
            commands += command
            val at = Instant.parse("2026-07-26T00:00:00Z")
            return ProvisioningStepResult(
                planId = command.planId,
                revision = command.revision,
                stepId = command.stepId,
                operationClass = command.operationClass,
                idempotencyKey = command.idempotencyKey,
                phase = command.phase,
                success = true,
                completedAt = at,
                preflight = com.duluin.ftth.contract.ProvisioningPreflightSnapshot(at, "hash"),
                verification = ProvisioningVerificationObservation(
                    observedAt = at,
                    matchesExpected = true,
                    stateHash = "hash",
                ),
            )
        }

        override fun capabilityReport(target: NasTarget) = DeviceCapabilityReport(
            targetId = target.nasId,
            fingerprint = DeviceFingerprint("MikroTik", "test", "7.20", "HTTPS_REST"),
            capabilities = setOf("CERTIFICATION_PROVISIONAL"),
            reportedAt = Instant.parse("2026-07-26T00:00:00Z"),
        )
    }
}

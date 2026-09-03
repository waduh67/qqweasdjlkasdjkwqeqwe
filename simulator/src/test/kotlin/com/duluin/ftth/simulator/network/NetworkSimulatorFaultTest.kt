package com.duluin.ftth.simulator.network

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NetworkSimulatorFaultTest {
    @Test
    fun `every mutation and verification boundary terminates without downstream work or leaked lock`() {
        val results = mutableListOf<String>()
        SimulatorFaultPoint.entries.forEach { point ->
            val faults = DeterministicFaultScript(mapOf(point to 1))
            val simulator = DeterministicNetworkSimulator(SimulatorProfiles.simulator, faults)

            val terminal = if (point == SimulatorFaultPoint.DURING_ROLLBACK) {
                simulator.create(VLAN)
                simulator.rollback()
            } else {
                simulator.create(VLAN)
            }

            assertTrue(terminal in setOf(SimulatorTerminalState.FAILED, SimulatorTerminalState.ROLLED_BACK), point.name)
            assertFalse(simulator.locked, point.name)
            assertFalse(simulator.hasDuplicateResources(), point.name)
            assertTrue(simulator.attemptCount <= DeterministicNetworkSimulator.MAX_ATTEMPTS, point.name)
            assertFalse(simulator.mutationsAfterFailure, point.name)
            assertEquals(expectedMutations(point), simulator.protocolTrace.filter { it.startsWith("MUTATE:") }, point.name)
            results += "${point.name}|${terminal.name}|attempts=${simulator.attemptCount}|locked=${simulator.locked}|duplicates=${simulator.hasDuplicateResources()}"
        }
        System.getenv("TASK17_EVIDENCE_DIR")?.let { directory ->
            val path = Path.of(directory).resolve("task-17-faults.txt")
            Files.createDirectories(path.parent)
            Files.writeString(path, results.joinToString(separator = "\n", postfix = "\n"))
        }
    }

    @Test
    fun `active sessions and management failure fail closed`() {
        val active = DeterministicNetworkSimulator(SimulatorProfiles.simulator)
        active.create(VLAN)
        active.activeSessions = 1
        assertEquals(SimulatorTerminalState.FAILED, active.delete(VLAN))
        assertTrue(active.state().bras.vlans.contains(VLAN))

        val managementDown = DeterministicNetworkSimulator(SimulatorProfiles.simulator)
        managementDown.managementAvailable = false
        assertEquals(SimulatorTerminalState.MANUAL_RECONCILIATION, managementDown.create(VLAN))
        assertTrue(managementDown.state().isEmpty())
    }

    private companion object {
        const val VLAN = 317

        fun expectedMutations(point: SimulatorFaultPoint): List<String> = when (point) {
            SimulatorFaultPoint.BEFORE_BRAS_MUTATION -> emptyList()
            SimulatorFaultPoint.AFTER_BRAS_MUTATION,
            SimulatorFaultPoint.BEFORE_TRANSIT_MUTATION,
            -> listOf("MUTATE:BRAS")
            SimulatorFaultPoint.AFTER_TRANSIT_MUTATION,
            SimulatorFaultPoint.BEFORE_OLT_MUTATION,
            -> listOf("MUTATE:BRAS", "MUTATE:TRANSIT")
            SimulatorFaultPoint.AFTER_OLT_MUTATION,
            SimulatorFaultPoint.DURING_VERIFICATION,
            SimulatorFaultPoint.DURING_ROLLBACK,
            -> listOf("MUTATE:BRAS", "MUTATE:TRANSIT", "MUTATE:OLT")
        }
    }
}

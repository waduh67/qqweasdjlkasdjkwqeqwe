package com.duluin.ftth.simulator.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NetworkSimulatorContractTest {
    @Test
    fun `every exact adapter profile passes the shared lifecycle contract`() {
        SimulatorProfiles.all.forEach { profile ->
            val simulator = DeterministicNetworkSimulator(profile)

            val created = simulator.create(VLAN)
            val verified = simulator.verify(VLAN)
            val repeated = simulator.create(VLAN)
            val rolledBack = simulator.rollback()
            val deleted = simulator.delete(VLAN)

            assertEquals(SimulatorTerminalState.SUCCEEDED, created, profile.id)
            assertTrue(verified, profile.id)
            assertEquals(SimulatorTerminalState.SUCCEEDED, repeated, profile.id)
            assertFalse(simulator.hasDuplicateResources(), profile.id)
            assertEquals(SimulatorTerminalState.ROLLED_BACK, rolledBack, profile.id)
            assertEquals(SimulatorTerminalState.SUCCEEDED, deleted, profile.id)
            assertTrue(simulator.state().isEmpty(), profile.id)
            assertFalse(simulator.locked, profile.id)
            assertTrue(simulator.supports("ENSURE_TAGGED_VLAN"), profile.id)
            assertEquals(
                "ENSURE_PPPOE_TERMINATION" in profile.supportedOperations,
                simulator.supports("ENSURE_PPPOE_TERMINATION"),
                profile.id,
            )
            assertEquals(
                SimulatorCapability.CONFIRMED_COMMIT in profile.capabilities,
                "CONFIRMED_COMMIT" in simulator.protocolTrace,
                profile.id,
            )
            assertEquals(SimulatorCapability.CONFIG_LOCK in profile.capabilities, "LOCK" in simulator.protocolTrace, profile.id)
            assertEquals(SimulatorCapability.CANDIDATE_CONFIG in profile.capabilities, "CANDIDATE" in simulator.protocolTrace, profile.id)
            assertEquals(SimulatorCapability.STRICT_PROMPTS in profile.capabilities, "PROMPT_OK" in simulator.protocolTrace, profile.id)
            assertEquals(SimulatorCapability.PERSISTENCE_RECONNECT in profile.capabilities, "PERSIST" in simulator.protocolTrace, profile.id)
            if (SimulatorCapability.REST_RESOURCE_IDS in profile.capabilities) {
                assertTrue(createdResourceIds(simulator, VLAN).all { it.startsWith('*') }, profile.id)
            }
        }
    }

    @Test
    fun `observation only preflight reports drift without mutation`() {
        val simulator = DeterministicNetworkSimulator(SimulatorProfiles.routerOs)
        simulator.create(VLAN)
        simulator.injectDrift(NetworkPlane.TRANSIT, VLAN + 1)
        val mutationsBefore = simulator.mutationCount

        val observation = simulator.observe()

        assertEquals(mutationsBefore, simulator.mutationCount)
        assertTrue(VLAN + 1 in observation.transit.vlans)
        assertFalse(observation.matches(VLAN))
    }

    @Test
    fun `persistence survives reconnect while volatile drift does not`() {
        val simulator = DeterministicNetworkSimulator(SimulatorProfiles.routerOs)
        simulator.create(VLAN)
        simulator.injectDrift(NetworkPlane.TRANSIT, VLAN + 1)

        simulator.reconnect()

        assertTrue(simulator.state().matches(VLAN))
        assertFalse(VLAN + 1 in simulator.state().transit.vlans)
    }

    private companion object {
        const val VLAN = 317

        fun createdResourceIds(simulator: DeterministicNetworkSimulator, vlanId: Int): List<String> {
            simulator.create(vlanId)
            val state = simulator.state()
            return listOf(state.bras, state.transit, state.olt).map { it.resourceIds.getValue(vlanId) }
        }
    }
}

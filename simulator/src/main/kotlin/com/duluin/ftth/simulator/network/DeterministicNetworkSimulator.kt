package com.duluin.ftth.simulator.network

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

enum class NetworkPlane { BRAS, TRANSIT, OLT }

enum class SimulatorTerminalState { SUCCEEDED, ROLLED_BACK, FAILED, MANUAL_RECONCILIATION }

data class NormalizedPlaneState(val vlans: Set<Int>, val resourceIds: Map<Int, String>)

data class NormalizedNetworkState(
    val bras: NormalizedPlaneState,
    val transit: NormalizedPlaneState,
    val olt: NormalizedPlaneState,
) {
    fun isEmpty(): Boolean = bras.vlans.isEmpty() && transit.vlans.isEmpty() && olt.vlans.isEmpty()
    fun matches(vlanId: Int): Boolean = listOf(bras, transit, olt).all { it.vlans == setOf(vlanId) }

    fun sha256(): String {
        val canonical = listOf(bras, transit, olt).joinToString("|") { plane ->
            plane.vlans.sorted().joinToString(",") { vlan -> "$vlan:${plane.resourceIds.getValue(vlan)}" }
        }
        return MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}

class DeterministicNetworkSimulator(
    val profile: SimulatorProfile,
    private val faults: DeterministicFaultScript = DeterministicFaultScript(),
) {
    private val resources = NetworkPlane.entries.associateWith { linkedMapOf<Int, String>() }
    private var durableResources = snapshotResources()
    private var baseline: Map<NetworkPlane, Map<Int, String>>? = null
    private var failureReached = false
    val protocolTrace = mutableListOf<String>()
    var activeSessions: Int = 0
    var managementAvailable: Boolean = true
    var mutationCount: Int = 0
        private set
    var attemptCount: Int = 0
        private set
    var mutationsAfterFailure: Boolean = false
        private set
    var locked: Boolean = false
        private set

    fun create(vlanId: Int): SimulatorTerminalState {
        require(vlanId in 2..4094) { "VLAN_OUT_OF_RANGE" }
        if (!managementAvailable) return SimulatorTerminalState.MANUAL_RECONCILIATION
        attemptCount += 1
        baseline = baseline ?: snapshotResources()
        lock()
        return try {
            mutate(NetworkPlane.BRAS, vlanId, SimulatorFaultPoint.BEFORE_BRAS_MUTATION, SimulatorFaultPoint.AFTER_BRAS_MUTATION)
            mutate(NetworkPlane.TRANSIT, vlanId, SimulatorFaultPoint.BEFORE_TRANSIT_MUTATION, SimulatorFaultPoint.AFTER_TRANSIT_MUTATION)
            mutate(NetworkPlane.OLT, vlanId, SimulatorFaultPoint.BEFORE_OLT_MUTATION, SimulatorFaultPoint.AFTER_OLT_MUTATION)
            faults.reach(SimulatorFaultPoint.DURING_VERIFICATION)
            require(state().matches(vlanId)) { "VERIFICATION_MISMATCH" }
            persistAndConfirm()
            SimulatorTerminalState.SUCCEEDED
        } catch (_: SimulatorInjectedFailure) {
            failureReached = true
            restoreBaseline()
            SimulatorTerminalState.ROLLED_BACK
        } finally {
            unlock()
        }
    }

    fun verify(vlanId: Int): Boolean {
        if (!managementAvailable) return false
        faults.reach(SimulatorFaultPoint.DURING_VERIFICATION)
        return state().matches(vlanId)
    }

    fun rollback(): SimulatorTerminalState {
        lock()
        return try {
            faults.reach(SimulatorFaultPoint.DURING_ROLLBACK)
            restoreBaseline()
            persistAndConfirm()
            SimulatorTerminalState.ROLLED_BACK
        } catch (_: SimulatorInjectedFailure) {
            failureReached = true
            SimulatorTerminalState.FAILED
        } finally {
            unlock()
        }
    }

    fun delete(vlanId: Int): SimulatorTerminalState {
        if (!managementAvailable) return SimulatorTerminalState.MANUAL_RECONCILIATION
        if (activeSessions > 0) return SimulatorTerminalState.FAILED
        lock()
        return try {
            NetworkPlane.entries.reversed().forEach { plane ->
                if (resources.getValue(plane).remove(vlanId) != null) mutationCount += 1
            }
            persistAndConfirm()
            SimulatorTerminalState.SUCCEEDED
        } finally {
            unlock()
        }
    }

    fun observe(): NormalizedNetworkState = state()

    fun supports(operationClass: String): Boolean = operationClass in profile.supportedOperations

    fun injectDrift(plane: NetworkPlane, vlanId: Int) {
        resources.getValue(plane)[vlanId] = resourceId(plane, vlanId)
    }

    fun reconnect() {
        resources.forEach { (plane, values) ->
            values.clear()
            values.putAll(durableResources.getValue(plane))
        }
        protocolTrace += "RECONNECT"
    }

    fun state(): NormalizedNetworkState = NormalizedNetworkState(
        planeState(NetworkPlane.BRAS),
        planeState(NetworkPlane.TRANSIT),
        planeState(NetworkPlane.OLT),
    )

    fun hasDuplicateResources(): Boolean = resources.values.any { values -> values.keys.size != values.keys.toSet().size }

    private fun mutate(plane: NetworkPlane, vlanId: Int, before: SimulatorFaultPoint, after: SimulatorFaultPoint) {
        faults.reach(before)
        if (failureReached) mutationsAfterFailure = true
        resources.getValue(plane).putIfAbsent(vlanId, resourceId(plane, vlanId))?.let { return }
        mutationCount += 1
        protocolTrace += "MUTATE:${plane.name}"
        faults.reach(after)
    }

    private fun lock() {
        check(!locked) { "SIMULATOR_LOCK_HELD" }
        locked = true
        if (SimulatorCapability.CONFIG_LOCK in profile.capabilities) protocolTrace += "LOCK"
        if (SimulatorCapability.CANDIDATE_CONFIG in profile.capabilities) protocolTrace += "CANDIDATE"
    }

    private fun unlock() {
        if (locked && SimulatorCapability.CONFIG_LOCK in profile.capabilities) protocolTrace += "UNLOCK"
        locked = false
    }

    private fun persistAndConfirm() {
        if (SimulatorCapability.CONFIRMED_COMMIT in profile.capabilities) protocolTrace += "CONFIRMED_COMMIT"
        if (SimulatorCapability.STRICT_PROMPTS in profile.capabilities) protocolTrace += "PROMPT_OK"
        if (SimulatorCapability.PERSISTENCE_RECONNECT in profile.capabilities) {
            durableResources = snapshotResources()
            protocolTrace += "PERSIST"
        }
    }

    private fun restoreBaseline() {
        val saved = baseline ?: return
        resources.forEach { (plane, values) ->
            values.clear()
            values.putAll(saved.getValue(plane))
        }
    }

    private fun snapshotResources(): Map<NetworkPlane, Map<Int, String>> =
        resources.mapValues { (_, values) -> values.toMap() }

    private fun planeState(plane: NetworkPlane): NormalizedPlaneState {
        val values = resources.getValue(plane).toSortedMap()
        return NormalizedPlaneState(values.keys, values)
    }

    private fun resourceId(plane: NetworkPlane, vlanId: Int): String =
        if (SimulatorCapability.REST_RESOURCE_IDS in profile.capabilities) "*${plane.ordinal + 1}$vlanId" else "${plane.name.lowercase()}-$vlanId"

    companion object {
        const val MAX_ATTEMPTS = 3
    }
}

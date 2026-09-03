package com.duluin.ftth.simulator.network

enum class SimulatorFaultPoint {
    BEFORE_BRAS_MUTATION,
    AFTER_BRAS_MUTATION,
    BEFORE_TRANSIT_MUTATION,
    AFTER_TRANSIT_MUTATION,
    BEFORE_OLT_MUTATION,
    AFTER_OLT_MUTATION,
    DURING_VERIFICATION,
    DURING_ROLLBACK,
}

class SimulatorInjectedFailure(val point: SimulatorFaultPoint) : RuntimeException(point.name)

class DeterministicFaultScript(failures: Map<SimulatorFaultPoint, Int> = emptyMap()) {
    private val remaining = failures.toMutableMap()

    fun reach(point: SimulatorFaultPoint) {
        val count = remaining[point] ?: return
        if (count <= 0) return
        remaining[point] = count - 1
        throw SimulatorInjectedFailure(point)
    }
}

enum class AckDisposition { DELIVER, LOST }

class DeterministicAckQueue {
    private data class PendingAck(val identity: String, val dueTick: Long, val disposition: AckDisposition)

    private val pending = mutableListOf<PendingAck>()
    var tick: Long = 0
        private set
    var lostCount: Int = 0
        private set

    fun enqueue(identity: String, delayTicks: Long, disposition: AckDisposition) {
        require(identity.isNotBlank()) { "ACK_IDENTITY_REQUIRED" }
        require(delayTicks >= 0) { "ACK_DELAY_INVALID" }
        pending += PendingAck(identity, tick + delayTicks.coerceAtLeast(1), disposition)
    }

    fun advance(): List<String> {
        tick += 1
        val due = pending.filter { it.dueTick <= tick }
        pending.removeAll(due.toSet())
        lostCount += due.count { it.disposition == AckDisposition.LOST }
        return due.filter { it.disposition == AckDisposition.DELIVER }.map(PendingAck::identity).sorted()
    }
}

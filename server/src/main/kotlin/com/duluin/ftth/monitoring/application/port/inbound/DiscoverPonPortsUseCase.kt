package com.duluin.ftth.monitoring.application.port.inbound

import java.time.Instant
import java.util.UUID

interface DiscoverPonPortsUseCase {
    fun discover(oltId: UUID): PonPortDiscovery
}

data class PonPortDiscovery(val oltId: UUID, val readAt: Instant, val ports: List<DetectedPonPort>, val warnings: List<String>)
data class DetectedPonPort(val label: String, val onuCount: Int?, val fromInterface: Boolean)

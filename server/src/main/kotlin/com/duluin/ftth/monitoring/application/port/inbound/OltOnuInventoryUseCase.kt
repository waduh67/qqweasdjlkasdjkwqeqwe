package com.duluin.ftth.monitoring.application.port.inbound

import java.time.Instant
import java.util.UUID

interface OltOnuInventoryUseCase {
    fun read(oltId: UUID): OltOnuInventory
}

data class OltOnuInventory(
    val oltId: UUID,
    val oltCode: String,
    val vendor: String,
    val systemDescription: String?,
    val readAt: Instant,
    val onus: List<OltOnuInventoryRow>,
    val warnings: List<String>,
)

data class OltOnuInventoryRow(
    val index: String,
    val ontId: String?,
    val name: String?,
    val serialNumber: String,
    val state: String?,
    val runningState: String?,
    val configState: String?,
    val deviceType: String?,
    val rxPowerDbm: Double?,
    val lastUpTime: String?,
    val lastDownTime: String?,
    val lastDownCause: String?,
)

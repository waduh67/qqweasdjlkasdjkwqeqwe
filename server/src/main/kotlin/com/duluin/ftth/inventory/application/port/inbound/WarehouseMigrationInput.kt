package com.duluin.ftth.inventory.application.port.inbound

data class WarehouseMigrationBeginInput(val expectedEpoch: Long, val expectedPreservationHash: String)

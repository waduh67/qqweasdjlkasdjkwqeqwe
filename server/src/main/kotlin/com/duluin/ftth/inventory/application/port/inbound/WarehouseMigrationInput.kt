package com.duluin.ftth.inventory.application.port.inbound

data class WarehouseMigrationBeginInput(val expectedEpoch: Long, val expectedPreservationHash: String)

data class WarehouseMigrationEvidenceInput(val expectedEpoch: Long, val expectedCaseHash: String, val label: String)

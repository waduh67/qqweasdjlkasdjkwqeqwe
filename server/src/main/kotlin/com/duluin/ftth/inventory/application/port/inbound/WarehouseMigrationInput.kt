package com.duluin.ftth.inventory.application.port.inbound

import com.duluin.ftth.inventory.AssetLegalOwner
import com.duluin.ftth.inventory.MigrationResolutionKind
import com.duluin.ftth.inventory.MigrationSourceUnit
import java.util.UUID

data class WarehouseMigrationBeginInput(val expectedEpoch: Long, val expectedPreservationHash: String)

data class WarehouseMigrationEvidenceInput(val expectedEpoch: Long, val expectedCaseHash: String, val label: String)

data class MigrationStockInput(val skuId: UUID, val sourceUnit: MigrationSourceUnit, val legalOwner: AssetLegalOwner)

data class WarehouseMigrationResolutionInput(val expectedEpoch: Long, val expectedCaseHash: String,
    val expectedResolutionRevision: Long, val kind: MigrationResolutionKind, val reason: String,
    val evidenceIds: List<UUID>, val stock: MigrationStockInput? = null, val duplicateCaseId: UUID? = null)

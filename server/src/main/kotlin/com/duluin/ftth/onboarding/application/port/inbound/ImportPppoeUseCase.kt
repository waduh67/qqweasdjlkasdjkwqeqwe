package com.duluin.ftth.onboarding.application.port.inbound

import com.duluin.ftth.common.domain.geo.Coordinate
import java.util.UUID

interface ImportPppoeUseCase {

    fun importPppoe(command: ImportPppoeCommand): ImportPppoeResult
}

enum class ImportSource { NAS, INLINE }

data class ImportPppoeCommand(
    val nasId: UUID,
    val source: ImportSource,
    val rows: List<ImportRow>,
    val profilePlanId: Map<String, UUID>,
    val defaultPlanId: UUID?,
    val skipDisabled: Boolean,
    val onlyNames: Set<String>?,
    val areaId: UUID?,
    val defaultAddress: String?,
    val defaultLocation: Coordinate?,
    val schemaVersion: Int = 1,
    val mode: ImportMode = ImportMode.ALREADY_INSTALLED,
    val operationKey: String? = null,
    val firstInvoiceOn: java.time.LocalDate? = null,
    val billingDay: Int? = null,
    val installationDate: java.time.LocalDate? = null,
)

data class ImportRow(
    val name: String,
    val password: String?,
    val profile: String?,
    val comment: String?,
    val disabled: Boolean,
)

data class ImportPppoeResult(
    val created: Int,
    val skipped: Int,
    val failed: Int,
    val rows: List<ImportRowOutcome>,
)

data class ImportRowOutcome(
    val username: String,
    val status: ImportRowStatus,
    val message: String?,
)

enum class ImportRowStatus { CREATED, SKIPPED, FAILED }

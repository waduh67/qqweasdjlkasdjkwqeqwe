package com.duluin.ftth.onboarding.application.port.inbound

import java.time.LocalDate

interface ImportCustomersUseCase {

    fun importCustomers(command: ImportCustomersCommand): ImportCustomersResult
}

enum class ImportMode {
    VALIDATE_ONLY, PENDING_INSTALLATION, ALREADY_INSTALLED, ALREADY_INSTALLED_RADIUS;
    val isMigration: Boolean get() = this == ALREADY_INSTALLED || this == ALREADY_INSTALLED_RADIUS
    val includesRadius: Boolean get() = this == ALREADY_INSTALLED_RADIUS
}

data class ImportCustomersCommand(
    val rows: List<CustomerImportRow>,
    val schemaVersion: Int = 1,
    val mode: ImportMode = ImportMode.ALREADY_INSTALLED,
    val operationKey: String? = null,
)

data class CustomerImportRow(
    val name: String?,
    val phone: String?,
    val address: String?,
    val packageName: String?,
    val connectionType: String?,
    val installationDate: LocalDate?,
    val mikrotikUsername: String?,
    val mikrotikPassword: String?,
    val email: String?,
    val routerName: String?,
    val idCardNumber: String?,
    val nextBillingDay: Int?,
    val latitude: Double?,
    val longitude: Double?,
    val framedIp: String? = null,
    val customerCode: String? = null,
    val serviceStatus: String = "ACTIVE",
    val firstInvoiceOn: LocalDate? = null,
    val monthlyFee: java.math.BigDecimal? = null,
    val areaId: java.util.UUID? = null,
    val onuSerial: String? = null,
)

data class ImportCustomersResult(
    val created: Int,
    val updated: Int,
    val skipped: Int,
    val failed: Int,
    val rows: List<CustomerImportOutcome>,
)

data class CustomerImportOutcome(
    val username: String,
    val status: CustomerImportStatus,
    val message: String?,
)

enum class CustomerImportStatus { CREATED, UPDATED, SKIPPED, FAILED }

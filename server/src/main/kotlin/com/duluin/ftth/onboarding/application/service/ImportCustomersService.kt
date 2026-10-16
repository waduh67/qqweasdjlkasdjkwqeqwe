package com.duluin.ftth.onboarding.application.service

import com.duluin.ftth.bng.BngApi
import com.duluin.ftth.bng.ImportedAccessRef
import com.duluin.ftth.catalog.CatalogApi
import com.duluin.ftth.common.domain.error.ConflictException
import com.duluin.ftth.common.domain.error.ValidationException
import com.duluin.ftth.common.domain.geo.Coordinate
import com.duluin.ftth.customer.CustomerApi
import com.duluin.ftth.customer.RegisterCustomerCommand
import com.duluin.ftth.customer.UpdateCustomerBiodataCommand
import com.duluin.ftth.onboarding.application.port.inbound.CustomerImportOutcome
import com.duluin.ftth.onboarding.application.port.inbound.CustomerImportRow
import com.duluin.ftth.onboarding.application.port.inbound.CustomerImportStatus
import com.duluin.ftth.onboarding.application.port.inbound.ImportCustomersCommand
import com.duluin.ftth.onboarding.application.port.inbound.ImportCustomersResult
import com.duluin.ftth.onboarding.application.port.inbound.ImportCustomersUseCase
import com.duluin.ftth.onboarding.application.port.inbound.ImportMode
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/** Each row is committed independently; retries are deduplicated by source identity. */
@Service
class ImportCustomersService(
    private val rowImporter: CustomerRowImporter,
) : ImportCustomersUseCase {

    override fun importCustomers(command: ImportCustomersCommand): ImportCustomersResult {
        val outcomes = command.rows.map { process(command, it) }
        return ImportCustomersResult(
            created = outcomes.count { it.status == CustomerImportStatus.CREATED },
            updated = outcomes.count { it.status == CustomerImportStatus.UPDATED },
            skipped = outcomes.count { it.status == CustomerImportStatus.SKIPPED },
            failed = outcomes.count { it.status == CustomerImportStatus.FAILED },
            rows = outcomes,
        )
    }

    private fun process(command: ImportCustomersCommand, row: CustomerImportRow): CustomerImportOutcome {
        val username = row.mikrotikUsername?.trim()?.takeIf(String::isNotEmpty) ?: row.customerCode?.trim().orEmpty()
        if (username.isEmpty()) {
            return CustomerImportOutcome("", CustomerImportStatus.SKIPPED, "Isi customer_code atau mikrotik_username")
        }
        // Petakan `connection_type` ke tipe kanonis di sini (murah, tanpa DB) sebelum membuka transaksi.
        val authType = resolveAuthType(row.connectionType)
            ?: return CustomerImportOutcome(
                username, CustomerImportStatus.FAILED,
                "Tipe koneksi '${row.connectionType}' tak dikenal (pppoe/hotspot/dhcp/static)",
            )
        if (command.mode == ImportMode.VALIDATE_ONLY) {
            return CustomerImportOutcome(username, CustomerImportStatus.SKIPPED, "VALIDATE_ONLY")
        }
        return try {
            CustomerImportOutcome(username, rowImporter.importRow(command, username, authType, row), null)
        } catch (e: ConflictException) {
            // Bentrok (mis. sudah pernah diimpor lewat jalur lain) → impor idempoten, lewati saja.
            CustomerImportOutcome(username, if (row.onuSerial.isNullOrBlank()) CustomerImportStatus.SKIPPED else CustomerImportStatus.FAILED, e.message)
        } catch (e: RuntimeException) {
            CustomerImportOutcome(username, CustomerImportStatus.FAILED, if (e is com.duluin.ftth.common.domain.error.DomainException) e.message else "Baris belum berhasil diproses")
        }
    }

    /**
     * Petakan `connection_type` (nilai bebas dari CSV/RouterOS) ke nama [AuthType] kanonis via substring
     * — toleran terhadap varian macam `pppoe_direct`. Kosong → PPPOE; tak dikenal → null (baris gagal).
     */
    private fun resolveAuthType(connectionType: String?): String? {
        val value = connectionType?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return "PPPOE"
        return when {
            value.contains("pppoe") -> "PPPOE"
            value.contains("hotspot") -> "HOTSPOT"
            value.contains("static") -> "STATIC"
            value.contains("dhcp") -> "DHCP"
            else -> null
        }
    }
}

/**
 * Menerapkan satu baris CSV dalam SATU transaksi fisik. Bean terpisah dari [ImportCustomersService]
 * AGAR proxy @Transactional benar-benar membuka transaksi baru per pemanggilan (self-invocation
 * takkan). Melempar bila gagal → transaksi baris roll-back utuh, ditangani pemanggil jadi outcome
 * per-baris. Upsert menurut username: tak ada akun → [createNew]; ada → [updateExisting].
 */
@Service
class CustomerRowImporter(
    private val customerApi: CustomerApi,
    private val catalogApi: CatalogApi,
    private val bngApi: BngApi,
    private val migration: CustomerMigrationRegistration,
) {

    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    fun importRow(
        command: ImportCustomersCommand,
        username: String,
        authType: String,
        row: CustomerImportRow,
    ): CustomerImportStatus {
        if (command.mode == ImportMode.ALREADY_INSTALLED) return createNew(command, username, authType, row)
        val existing = bngApi.findAccessByUsername(username)
        return if (existing == null) createNew(command, username, authType, row) else updateExisting(existing, row)
    }

    /** Pelanggan+langganan+akun BARU, langsung aktif (pelanggan impor sudah terpasang di lapangan). */
    private fun createNew(command: ImportCustomersCommand, username: String, authType: String, row: CustomerImportRow): CustomerImportStatus {
        val plan = catalogApi.findPlanByName(row.packageName.orEmpty())
            ?: throw ValidationException("Paket '${row.packageName ?: "-"}' tidak ditemukan")
        val nasId = resolveNas(row.routerName)
        val customer = RegisterCustomerCommand(
                code = row.customerCode,
                name = row.name?.trim().orEmpty(),
                phone = row.phone?.trim()?.takeIf { it.isNotEmpty() },
                email = row.email?.trim()?.takeIf { it.isNotEmpty() },
                address = row.address?.trim().orEmpty(),
                location = coordinateOf(row),
                areaId = row.areaId,
                idCardNumber = row.idCardNumber?.trim()?.takeIf { it.isNotEmpty() },
                planId = plan.planId,
                monthlyFeeOverride = row.monthlyFee,
            )
        if (command.mode == ImportMode.ALREADY_INSTALLED) {
            val status = when (row.serviceStatus.trim().uppercase()) {
                "ACTIVE", "AKTIF" -> MigrationStatus.ACTIVE
                "SUSPENDED", "ISOLATED", "DITANGGUHKAN", "TERISOLIR" -> MigrationStatus.SUSPENDED
                else -> throw ValidationException("Status layanan harus ACTIVE atau SUSPENDED")
            }
            migration.register(CustomerMigrationCommand(
                operationKey = (command.operationKey?.let { "$it:" } ?: "csv:") + username,
                customer = customer, status = status, installationDate = row.installationDate,
                firstInvoiceOn = row.firstInvoiceOn, billingDay = row.nextBillingDay, source = MigrationSource.CSV,
                username = row.mikrotikUsername, password = row.mikrotikPassword, authType = authType,
                nasId = nasId, framedIp = row.framedIp,
                onuSerial = row.onuSerial,
            ))
        } else customerApi.registerCustomer(customer)
        return CustomerImportStatus.CREATED
    }

    /** Perbarui parsial akun yang sudah ada. Paket TAK diubah (kunci = username, bukan paket). */
    private fun updateExisting(existing: ImportedAccessRef, row: CustomerImportRow): CustomerImportStatus {
        customerApi.updateCustomerBiodata(
            UpdateCustomerBiodataCommand(
                customerId = existing.customerId,
                name = row.name?.trim()?.takeIf { it.isNotEmpty() },
                phone = row.phone?.trim()?.takeIf { it.isNotEmpty() },
                email = row.email?.trim()?.takeIf { it.isNotEmpty() },
                address = row.address?.trim()?.takeIf { it.isNotEmpty() },
                location = coordinateOf(row),
                idCardNumber = row.idCardNumber?.trim()?.takeIf { it.isNotEmpty() },
            ),
        )
        // Tanggal tagih hanya disetel bila kolom membawanya (kosong = biarkan snapshot paket).
        row.nextBillingDay?.let {
            customerApi.overrideSubscriptionBillingDay(existing.subscriptionId, it)
        }
        if (!row.routerName.isNullOrBlank()) resolveNas(row.routerName)
        return CustomerImportStatus.UPDATED
    }

    /** Resolusi BRAS menurut nama; kosong → null (akun tanpa BRAS), nama tak dikenal → gagal baris. */
    private fun resolveNas(routerName: String?): UUID? {
        val name = routerName?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return bngApi.resolveNasByName(name) ?: throw ValidationException("Router '$name' tidak ditemukan")
    }

    private fun coordinateOf(row: CustomerImportRow): Coordinate? {
        if ((row.latitude == null) != (row.longitude == null)) throw ValidationException("Isi lintang dan bujur bersama")
        val lat = row.latitude ?: return null
        val lon = row.longitude ?: return null
        if (!lat.isFinite() || !lon.isFinite() || lat !in -90.0..90.0 || lon !in -180.0..180.0) throw ValidationException("Koordinat tidak valid")
        if (lon == 0.0 && lat == 0.0) return null
        return Coordinate(lon, lat)
    }
}

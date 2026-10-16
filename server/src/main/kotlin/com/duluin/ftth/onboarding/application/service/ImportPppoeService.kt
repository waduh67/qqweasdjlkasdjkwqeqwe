package com.duluin.ftth.onboarding.application.service

import com.duluin.ftth.bng.BngApi
import com.duluin.ftth.common.domain.error.ConflictException
import com.duluin.ftth.common.domain.geo.Coordinate
import com.duluin.ftth.customer.CustomerApi
import com.duluin.ftth.customer.RegisterCustomerCommand
import com.duluin.ftth.onboarding.application.port.inbound.ImportPppoeCommand
import com.duluin.ftth.onboarding.application.port.inbound.ImportPppoeResult
import com.duluin.ftth.onboarding.application.port.inbound.ImportPppoeUseCase
import com.duluin.ftth.onboarding.application.port.inbound.ImportRow
import com.duluin.ftth.onboarding.application.port.inbound.ImportRowOutcome
import com.duluin.ftth.onboarding.application.port.inbound.ImportRowStatus
import com.duluin.ftth.onboarding.application.port.inbound.ImportSource
import com.duluin.ftth.onboarding.application.port.inbound.ImportMode
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/** Placeholder alamat pelanggan impor — `/ppp/secret` tak punya alamat; operator memperkayanya. */
private const val PLACEHOLDER_ADDRESS = "Belum diisi"

/**
 * Orkestrasi bulk-import PPPoE — module daun yang hanya memanggil kontrak publik bng & customer.
 * Loop di sini SENGAJA tak transaksional: tiap baris diproses [PppoeRowImporter.importRow] yang
 * @Transactional (REQUIRED — tanpa tx berjalan berarti satu transaksi fisik per baris), sehingga
 * satu baris gagal roll-back sendiri tanpa menyeret batch. Sumber baris NAS ditarik server-side
 * ([BngApi.fetchPppSecretsFromNas], password tak pernah lewat browser); INLINE dari muatan operator.
 */
@Service
class ImportPppoeService(
    private val bngApi: BngApi,
    private val rowImporter: PppoeRowImporter,
) : ImportPppoeUseCase {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun importPppoe(command: ImportPppoeCommand): ImportPppoeResult {
        val allRows = when (command.source) {
            ImportSource.NAS -> bngApi.fetchPppSecretsFromNas(command.nasId).map {
                ImportRow(it.name, it.password, it.profile, it.comment, it.disabled)
            }
            ImportSource.INLINE -> command.rows
        }
        val selected = allRows
            .filter { it.name.isNotBlank() }
            .filter { command.onlyNames == null || it.name in command.onlyNames }
            .filter { !command.skipDisabled || !it.disabled }

        val outcomes = selected.map { row -> process(command, row) }
        return ImportPppoeResult(
            created = outcomes.count { it.status == ImportRowStatus.CREATED },
            skipped = outcomes.count { it.status == ImportRowStatus.SKIPPED },
            failed = outcomes.count { it.status == ImportRowStatus.FAILED },
            rows = outcomes,
        )
    }

    private fun process(command: ImportPppoeCommand, row: ImportRow): ImportRowOutcome {
        val planId = command.profilePlanId[row.profile] ?: command.defaultPlanId
            ?: return ImportRowOutcome(
                row.name, ImportRowStatus.SKIPPED, "Profil '${row.profile ?: "-"}' belum dipetakan ke paket",
            )
        return try {
            if (command.mode == ImportMode.VALIDATE_ONLY) {
                return ImportRowOutcome(row.name, ImportRowStatus.SKIPPED, "VALIDATE_ONLY")
            }
            rowImporter.importRow(command, row, planId)
            ImportRowOutcome(row.name, ImportRowStatus.CREATED, null)
        } catch (e: ConflictException) {
            // Kode/username sudah ada → anggap sudah pernah diimpor; impor idempoten, aman diulang.
            ImportRowOutcome(row.name, ImportRowStatus.SKIPPED, e.message)
        } catch (e: RuntimeException) {
            log.warn("Impor PPPoE gagal untuk '{}': {}", row.name, e.message)
            ImportRowOutcome(row.name, ImportRowStatus.FAILED, if (e is com.duluin.ftth.common.domain.error.DomainException) e.message else "Akun belum berhasil diproses")
        }
    }
}

/** An imported row has its own transaction, independent of the rest of the batch. */
@Service
class PppoeRowImporter(
    private val customerApi: CustomerApi,
    private val migration: CustomerMigrationRegistration,
) {
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    fun importRow(command: ImportPppoeCommand, row: ImportRow, planId: UUID) {
        val customer = RegisterCustomerCommand(
            code = null,
            name = row.comment?.trim()?.takeIf(String::isNotEmpty) ?: row.name,
            phone = null, email = null,
            address = command.defaultAddress?.trim()?.takeIf(String::isNotEmpty) ?: PLACEHOLDER_ADDRESS,
            location = command.defaultLocation, areaId = command.areaId, planId = planId,
        )
        if (command.mode == ImportMode.ALREADY_INSTALLED) {
            migration.register(CustomerMigrationCommand(
                operationKey = (command.operationKey?.let { "$it:" } ?: "mikrotik:") + row.name,
                customer = customer, status = if (row.disabled) MigrationStatus.SUSPENDED else MigrationStatus.ACTIVE,
                source = MigrationSource.MIKROTIK, username = row.name, password = row.password, nasId = command.nasId,
                installationDate = command.installationDate, firstInvoiceOn = command.firstInvoiceOn, billingDay = command.billingDay,
            ))
        } else customerApi.registerCustomer(customer)
    }
}

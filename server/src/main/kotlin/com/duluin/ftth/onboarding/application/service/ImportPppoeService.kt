package com.duluin.ftth.onboarding.application.service

import com.duluin.ftth.bng.BngApi
import com.duluin.ftth.bng.ProvisionAccessSpec
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
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/** Placeholder alamat pelanggan impor — `/ppp/secret` tak punya alamat; operator memperkayanya. */
private const val PLACEHOLDER_ADDRESS = "(impor PPPoE — lengkapi alamat)"

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
            rowImporter.importRow(command, row, planId)
            ImportRowOutcome(row.name, ImportRowStatus.CREATED, null)
        } catch (e: ConflictException) {
            // Kode/username sudah ada → anggap sudah pernah diimpor; impor idempoten, aman diulang.
            ImportRowOutcome(row.name, ImportRowStatus.SKIPPED, e.message)
        } catch (e: RuntimeException) {
            log.warn("Impor PPPoE gagal untuk '{}': {}", row.name, e.message)
            ImportRowOutcome(row.name, ImportRowStatus.FAILED, e.message)
        }
    }
}

/**
 * Menyalin satu baris `/ppp/secret` menjadi pelanggan+langganan+akun dalam SATU transaksi fisik:
 * daftar pelanggan → buka langganan → aktifkan (prorata dari kini) → provisi akun PPPoE (lahir
 * ACTIVE karena langganan sudah aktif + ber-BRAS → server menulis identitas ke RADIUS pusat).
 * Bean terpisah dari [ImportPppoeService] AGAR proxy @Transactional benar-benar membuka transaksi
 * baru per pemanggilan (self-invocation takkan). Melempar bila gagal → transaksi baris roll-back
 * utuh, ditangani pemanggil jadi outcome per-baris.
 */
@Service
class PppoeRowImporter(
    private val customerApi: CustomerApi,
    private val bngApi: BngApi,
) {

    @Transactional
    fun importRow(command: ImportPppoeCommand, row: ImportRow, planId: UUID) {
        val customerId = customerApi.registerCustomer(
            RegisterCustomerCommand(
                code = row.name,
                name = row.comment?.trim()?.takeIf { it.isNotEmpty() } ?: row.name,
                phone = null,
                email = null,
                address = command.defaultAddress?.trim()?.takeIf { it.isNotEmpty() } ?: PLACEHOLDER_ADDRESS,
                location = command.defaultLocation ?: Coordinate(0.0, 0.0),
                areaId = command.areaId,
            ),
        )
        val subscriptionId = customerApi.openSubscription(customerId, planId, null)
        // Pelanggan impor sudah terpasang di lapangan → langsung aktif (tanpa WO PSB). Aktivasi
        // memprorata tagihan dari kini — konsekuensi yang direlay ke operator di UI/panduan.
        customerApi.activateForInstallation(subscriptionId)
        bngApi.provisionAccess(
            ProvisionAccessSpec(
                subscriptionId = subscriptionId,
                username = row.name,
                secret = row.password,
                planId = planId,
                nasId = command.nasId,
                authType = "PPPOE",
                framedIp = null,
            ),
        )
    }
}

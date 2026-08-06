package com.duluin.ftth.onboarding.application.service

import com.duluin.ftth.bng.BngApi
import com.duluin.ftth.bng.ImportedAccessRef
import com.duluin.ftth.bng.ProvisionAccessSpec
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
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.ZoneOffset
import java.util.UUID

/** Koordinat placeholder saat baris CSV tak membawa lat/long — operator memperkayanya belakangan. */
private val PLACEHOLDER_LOCATION = Coordinate(0.0, 0.0)

/** Batas atas hari tanggal tagih: hari >28 di-clamp ke 28 agar tetap ada di tiap bulan (Februari). */
private const val MAX_BILLING_DAY = 28

/**
 * Orkestrasi impor CSV pelanggan — module daun yang hanya memanggil kontrak publik customer,
 * catalog, & bng. Loop di sini SENGAJA tak transaksional: tiap baris diproses [CustomerRowImporter]
 * yang @Transactional (satu transaksi fisik per baris), sehingga satu baris gagal roll-back sendiri
 * tanpa menyeret batch. Validasi murah yang tak butuh DB (username kosong, tipe koneksi) disaring di
 * sini sebelum membuka transaksi.
 */
@Service
class ImportCustomersService(
    private val rowImporter: CustomerRowImporter,
) : ImportCustomersUseCase {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun importCustomers(command: ImportCustomersCommand): ImportCustomersResult {
        val outcomes = command.rows.map { process(it) }
        return ImportCustomersResult(
            created = outcomes.count { it.status == CustomerImportStatus.CREATED },
            updated = outcomes.count { it.status == CustomerImportStatus.UPDATED },
            skipped = outcomes.count { it.status == CustomerImportStatus.SKIPPED },
            failed = outcomes.count { it.status == CustomerImportStatus.FAILED },
            rows = outcomes,
        )
    }

    private fun process(row: CustomerImportRow): CustomerImportOutcome {
        val username = row.mikrotikUsername?.trim().orEmpty()
        if (username.isEmpty()) {
            return CustomerImportOutcome("", CustomerImportStatus.SKIPPED, "mikrotik_username kosong — baris dilewati")
        }
        // Petakan `connection_type` ke tipe kanonis di sini (murah, tanpa DB) sebelum membuka transaksi.
        val authType = resolveAuthType(row.connectionType)
            ?: return CustomerImportOutcome(
                username, CustomerImportStatus.FAILED,
                "Tipe koneksi '${row.connectionType}' tak dikenal (pppoe/hotspot/dhcp/static)",
            )
        return try {
            CustomerImportOutcome(username, rowImporter.importRow(username, authType, row), null)
        } catch (e: ConflictException) {
            // Bentrok (mis. sudah pernah diimpor lewat jalur lain) → impor idempoten, lewati saja.
            CustomerImportOutcome(username, CustomerImportStatus.SKIPPED, e.message)
        } catch (e: RuntimeException) {
            log.warn("Impor pelanggan gagal untuk '{}': {}", username, e.message)
            CustomerImportOutcome(username, CustomerImportStatus.FAILED, e.message)
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
) {

    @Transactional
    fun importRow(username: String, authType: String, row: CustomerImportRow): CustomerImportStatus {
        val existing = bngApi.findAccessByUsername(username)
        return if (existing == null) createNew(username, authType, row) else updateExisting(existing, row)
    }

    /** Pelanggan+langganan+akun BARU, langsung aktif (pelanggan impor sudah terpasang di lapangan). */
    private fun createNew(username: String, authType: String, row: CustomerImportRow): CustomerImportStatus {
        val plan = catalogApi.findPlanByName(row.packageName.orEmpty())
            ?: throw ValidationException("Paket '${row.packageName ?: "-"}' tidak ditemukan")
        val nasId = resolveNas(row.routerName)
        val customerId = customerApi.registerCustomer(
            RegisterCustomerCommand(
                code = null,
                name = row.name?.trim().orEmpty(),
                phone = row.phone?.trim()?.takeIf { it.isNotEmpty() },
                email = row.email?.trim()?.takeIf { it.isNotEmpty() },
                address = row.address?.trim().orEmpty(),
                location = coordinateOf(row) ?: PLACEHOLDER_LOCATION,
                areaId = null,
                idCardNumber = row.idCardNumber?.trim()?.takeIf { it.isNotEmpty() },
            ),
        )
        val subscriptionId = customerApi.openSubscription(customerId, plan.planId, null)
        customerApi.activateImportedSubscription(
            subscriptionId,
            row.installationDate?.atStartOfDay(ZoneOffset.UTC)?.toInstant(),
            clampBillingDay(row.nextBillingDay),
        )
        bngApi.provisionAccess(
            ProvisionAccessSpec(
                subscriptionId = subscriptionId,
                username = username,
                // Password hanya untuk tipe login (PPPoE/Hotspot); MAC-based (DHCP/Static) mengabaikannya.
                secret = row.mikrotikPassword?.trim()?.takeIf { it.isNotEmpty() },
                planId = plan.planId,
                nasId = nasId,
                authType = authType,
                // Framed-IP hanya bermakna untuk Static (wajib) / DHCP (opsional); bng memvalidasi.
                framedIp = row.framedIp?.trim()?.takeIf { it.isNotEmpty() },
            ),
        )
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
            customerApi.overrideSubscriptionBillingDay(existing.subscriptionId, clampBillingDay(it))
        }
        // BRAS diperbarui bila router_name diisi; kosong = pertahankan BRAS lama. Password kosong
        // = pertahankan (ditangani di dalam updateAccessFromImport). Paket tetap (existing.planId).
        val nasId = if (row.routerName.isNullOrBlank()) existing.nasId else resolveNas(row.routerName)
        bngApi.updateAccessFromImport(existing.accessId, existing.planId, nasId, row.mikrotikPassword)
        return CustomerImportStatus.UPDATED
    }

    /** Resolusi BRAS menurut nama; kosong → null (akun tanpa BRAS), nama tak dikenal → gagal baris. */
    private fun resolveNas(routerName: String?): UUID? {
        val name = routerName?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return bngApi.resolveNasByName(name) ?: throw ValidationException("Router '$name' tidak ditemukan")
    }

    /** Koordinat baris bila lat & long lengkap; salah satu kosong → null (jalur update: pertahankan). */
    private fun coordinateOf(row: CustomerImportRow): Coordinate? {
        val lat = row.latitude ?: return null
        val lon = row.longitude ?: return null
        return Coordinate(lon, lat)
    }

    /** Opsi A: hari tanggal tagih >28 di-clamp ke 28; null tetap null (ikut kebijakan billing). */
    private fun clampBillingDay(day: Int?): Int? = day?.let { minOf(it, MAX_BILLING_DAY) }
}

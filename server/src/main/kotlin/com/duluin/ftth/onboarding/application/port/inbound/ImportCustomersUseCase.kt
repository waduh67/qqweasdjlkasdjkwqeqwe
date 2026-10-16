package com.duluin.ftth.onboarding.application.port.inbound

import java.time.LocalDate

/** Import existing service records without changing network authentication. */
interface ImportCustomersUseCase {

    fun importCustomers(command: ImportCustomersCommand): ImportCustomersResult
}

enum class ImportMode { VALIDATE_ONLY, PENDING_INSTALLATION, ALREADY_INSTALLED }

/** Perintah impor: kumpulan baris CSV yang sudah diurai klien menjadi bentuk terstruktur. */
data class ImportCustomersCommand(
    val rows: List<CustomerImportRow>,
    val schemaVersion: Int = 1,
    val mode: ImportMode = ImportMode.ALREADY_INSTALLED,
    val operationKey: String? = null,
)

/**
 * Satu baris CSV pelanggan (sudah diurai klien). [mikrotikUsername] = kunci upsert. Semua field
 * lain opsional: pada jalur UPDATE, kolom kosong berarti "pertahankan yang ada"; pada jalur CREATE,
 * field wajib domain (nama, alamat) yang kosong membuat baris GAGAL. [connectionType] kosong/`pppoe`
 * → PPPoE, `hotspot`/`dhcp`/`static` → tipe terkait; tak dikenal → baris GAGAL. Untuk `dhcp`/`static`
 * [mikrotikUsername] adalah MAC & [mikrotikPassword] diabaikan; [framedIp] jadi reservasi Framed-IP
 * (WAJIB `static`, opsional `dhcp`, diabaikan `pppoe`/`hotspot`). [installationDate] jadi tanggal
 * aktivasi langganan. [nextBillingDay] = hari tanggal tagih (di-clamp ≤28). [latitude]/[longitude] →
 * koordinat pelanggan.
 */
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
)

/** Rekap hasil impor + rincian per-baris. */
data class ImportCustomersResult(
    val created: Int,
    val updated: Int,
    val skipped: Int,
    val failed: Int,
    val rows: List<CustomerImportOutcome>,
)

/**
 * Nasib satu baris. [username] = `mikrotik_username` baris (kunci upsert; kosong bila baris tak
 * membawanya). [message] menjelaskan alasan SKIPPED/FAILED, null bila sukses.
 */
data class CustomerImportOutcome(
    val username: String,
    val status: CustomerImportStatus,
    val message: String?,
)

/**
 * CREATED = pelanggan+langganan+akun baru dibuat; UPDATED = akun yang sudah ada diperbarui;
 * SKIPPED = sengaja dilewati (username kosong / sudah pernah diimpor);
 * FAILED = gagal (data tak valid / tipe koneksi tak dikenal / paket/router tak ditemukan / MAC atau
 * Framed-IP tak valid).
 */
enum class CustomerImportStatus { CREATED, UPDATED, SKIPPED, FAILED }

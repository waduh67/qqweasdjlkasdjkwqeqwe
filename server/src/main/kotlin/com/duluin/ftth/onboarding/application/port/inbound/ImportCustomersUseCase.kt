package com.duluin.ftth.onboarding.application.port.inbound

import java.time.LocalDate

/**
 * Impor CSV pelanggan generik — satu berkas berisi biodata + langganan + akun jaringan per baris,
 * di-UPSERT menurut `mikrotik_username`. Beda dari [ImportPppoeUseCase] yang khusus menyedot
 * `/ppp/secret` sebuah RouterOS: sumbernya berkas CSV yang dirakit operator (kolom lengkap:
 * nama, alamat, koordinat, paket, tanggal pasang, tanggal tagih), dan bersifat UPSERT — bukan
 * sekadar create.
 *
 * Kunci upsert = username akun jaringan (`mikrotik_username`):
 *  - belum ada  → BUAT pelanggan + langganan (paket dari `package_name`) + akun PPPoE, langsung
 *                 AKTIF (pelanggan impor sudah terpasang), aktivasi memakai `installation_date`.
 *  - sudah ada  → PERBARUI parsial: biodata (kolom kosong dilewati), tanggal tagih, BRAS, dan
 *                 password (kosong = pertahankan). Paket SENGAJA tak diubah pada jalur update —
 *                 ganti paket bukan lingkup impor massal ini.
 *
 * Per-baris ATOMIK (tiap baris commit sendiri): satu baris gagal tak menyeret batch. Hasil
 * per-baris ([CustomerImportOutcome]).
 *
 * Cakupan v1 (dilaporkan ke operator): hanya koneksi PPPoE; baris tipe lain dilewati. Aktivasi
 * MEMPRORATA dari `installation_date` (lampau → tagih penuh, tak ada masalah prorata). `next_billing`
 * dipetakan ke HARI tanggal tagih (Opsi A), di-clamp ≤28 agar aman di Februari; kolom `notes`
 * diabaikan.
 */
interface ImportCustomersUseCase {

    fun importCustomers(command: ImportCustomersCommand): ImportCustomersResult
}

/** Perintah impor: kumpulan baris CSV yang sudah diurai klien menjadi bentuk terstruktur. */
data class ImportCustomersCommand(
    val rows: List<CustomerImportRow>,
)

/**
 * Satu baris CSV pelanggan (sudah diurai klien). [mikrotikUsername] = kunci upsert. Semua field
 * lain opsional: pada jalur UPDATE, kolom kosong berarti "pertahankan yang ada"; pada jalur CREATE,
 * field wajib domain (nama, alamat) yang kosong membuat baris GAGAL. [connectionType] "pppoe_direct"/
 * kosong → PPPoE; tipe lain dilewati (v1). [installationDate] jadi tanggal aktivasi langganan.
 * [nextBillingDay] = hari tanggal tagih (di-clamp ≤28). [latitude]/[longitude] → koordinat pelanggan.
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
 * SKIPPED = sengaja dilewati (username kosong / tipe koneksi tak didukung / sudah pernah diimpor);
 * FAILED = gagal (data tak valid / paket/router tak ditemukan).
 */
enum class CustomerImportStatus { CREATED, UPDATED, SKIPPED, FAILED }

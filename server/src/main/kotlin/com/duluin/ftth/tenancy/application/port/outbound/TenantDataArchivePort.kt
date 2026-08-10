package com.duluin.ftth.tenancy.application.port.outbound

import java.io.OutputStream
import java.time.Instant
import java.util.UUID

/**
 * Menulis SELURUH data satu tenant sebagai arsip (satu CSV per tabel) ke [target].
 *
 * Sengaja port tersendiri, bukan kumpulan pemanggilan repository tiap module: isi arsip
 * harus LENGKAP, dan daftar tabel yang ditulis tangan akan diam-diam basi setiap kali ada
 * module baru — persis kesalahan yang paling mahal di sini, karena yang hilang baru
 * ketahuan setelah tenantnya pergi. Implementasinya menemukan tabel dari katalog database
 * (cerminan pendekatan yang sama pada penghapusan tenant), sehingga tabel baru ikut
 * terekspor tanpa ada yang perlu ingat.
 */
interface TenantDataArchivePort {

    /**
     * Tulis arsip data [tenantId] ke [target]; [target] TIDAK ditutup (pemanggilnya —
     * lazimnya aliran respons HTTP — yang memilikinya). Mengembalikan rekap isi arsip
     * untuk dicatat/di-audit.
     */
    fun writeArchive(tenantId: UUID, target: OutputStream): TenantExportReport
}

/**
 * Rekap satu ekspor: apa yang masuk arsip, apa yang tidak, dan mengapa. [skipped] berisi
 * alasan per tabel yang sengaja dilewati — ada di sini (bukan hanya di dalam arsip) supaya
 * keputusan "tak semua ikut" tak pernah tak terlihat oleh pemanggil.
 */
data class TenantExportReport(
    val generatedAt: Instant,
    val tables: List<TenantExportTable>,
    val skipped: Map<String, String>,
) {
    val rowCount: Long get() = tables.sumOf { it.rows }
}

/** Satu tabel dalam arsip: berapa baris ikut, dan kolom mana yang isinya disunting. */
data class TenantExportTable(
    val name: String,
    val rows: Long,
    val redactedColumns: List<String>,
)

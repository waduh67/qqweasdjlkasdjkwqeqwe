package com.duluin.ftth.tenancy.application.port.inbound

import com.duluin.ftth.tenancy.application.port.outbound.TenantExportReport
import java.io.OutputStream

/**
 * Membawa pulang seluruh data tenant sendiri — hak yang harus ada sebelum tenant memutuskan
 * berhenti, bukan sesudahnya: sistem yang datanya tak bisa keluar sebetulnya menyandera
 * penggunanya, dan permintaan ekspor justru paling mungkin datang saat hubungan sedang tak
 * baik. Karena itu ini fitur swalayan bagi tenant, bukan permohonan ke admin platform.
 */
interface ExportTenantDataUseCase {

    /**
     * Nama berkas arsip untuk tenant aktif (mis. `netops-demo-2026-08-10.zip`). Terpisah dari
     * [exportCurrentTenant] karena header `Content-Disposition` harus terkirim SEBELUM satu
     * bita isi pun ditulis — dan isinya di-stream, bukan disusun lebih dulu di memori.
     */
    fun archiveName(): String

    /**
     * Tulis arsip data tenant yang sedang login ke [target] (tak ditutup), lalu kembalikan
     * rekap isinya. Selalu tenant milik pengguna: ekspor lintas-tenant tak pernah dibutuhkan
     * operator, dan menyediakannya berarti satu jalan lagi menuju kebocoran antar-tenant.
     */
    fun exportCurrentTenant(target: OutputStream): TenantExportReport
}

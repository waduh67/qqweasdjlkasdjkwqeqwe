package com.duluin.ftth.onboarding.application.port.inbound

import java.time.LocalDate

/**
 * Ekspor CSV pelanggan — kebalikan simetris dari [ImportCustomersUseCase]. Merakit satu baris per
 * AKUN jaringan (anchor = `mikrotik_username`, kunci upsert impor) dengan menggabungkan biodata
 * pelanggan, snapshot langganan, dan identitas jaringan (BRAS, tipe koneksi) melalui kontrak publik
 * customer & bng.
 *
 * Sengaja TANPA password: `mikrotik_password` selalu kosong di keluaran (rahasia tak pernah menembus
 * batas module, dan impor ulang memperlakukan kolom kosong sebagai "pertahankan"). Kolom `notes`
 * juga kosong (tak dipetakan ke model). Hasilnya dapat diimpor kembali (round-trip) lewat template
 * yang sama.
 */
interface ExportCustomersUseCase {

    fun exportCustomers(): List<CustomerExportLine>
}

/**
 * Satu baris ekspor CSV pelanggan (sebelum diserialisasi adapter web). [mikrotikUsername] = anchor
 * (selalu ada). [connectionType] nama tipe koneksi huruf kecil (mis. "pppoe"). [installationDate]
 * diturunkan dari tanggal aktivasi langganan (tanggal UTC). Kolom rahasia (`mikrotik_password`) dan
 * `notes` sengaja tak ada di sini — adapter menulisnya kosong agar tetap cocok template impor.
 */
data class CustomerExportLine(
    val name: String?,
    val phone: String?,
    val address: String?,
    val packageName: String?,
    val connectionType: String,
    val installationDate: LocalDate?,
    val mikrotikUsername: String,
    val email: String?,
    val routerName: String?,
    val idCardNumber: String?,
    val nextBillingDay: Int?,
    val latitude: Double?,
    val longitude: Double?,
)

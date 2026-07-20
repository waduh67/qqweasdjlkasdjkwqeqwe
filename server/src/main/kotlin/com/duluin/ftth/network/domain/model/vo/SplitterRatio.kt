package com.duluin.ftth.network.domain.model.vo

import com.duluin.ftth.common.domain.error.ValidationException

/**
 * Rasio splitter pasif beserta rugi sisipan (insertion loss) tipikalnya.
 *
 * Loss dibawa di sini, bukan di tabel konfigurasi, karena nilainya properti fisika
 * komponen — bukan sesuatu yang di-tuning per tenant. Dipakai untuk menghitung
 * anggaran redaman sepanjang jalur OLT→pelanggan.
 */
enum class SplitterRatio(val label: String, val splitCount: Int, val insertionLossDb: Double) {
    ONE_TO_2("1:2", 2, 3.6),
    ONE_TO_4("1:4", 4, 7.2),
    ONE_TO_8("1:8", 8, 10.5),
    ONE_TO_16("1:16", 16, 13.8),
    ONE_TO_32("1:32", 32, 17.1),
    ONE_TO_64("1:64", 64, 20.5),
    ;

    companion object {
        fun of(label: String): SplitterRatio = entries.firstOrNull { it.label == label.trim() }
            ?: throw ValidationException("Rasio splitter tidak dikenal: '$label'. Pilihan: ${entries.joinToString { it.label }}")
    }
}

package com.duluin.ftth.network.domain.model

import com.duluin.ftth.common.domain.error.ValidationException

/**
 * Aturan penamaan yang seragam untuk seluruh aset jaringan. Dipusatkan di sini
 * supaya kode ODP dan kode ODC tidak diam-diam punya aturan berbeda.
 */
internal object AssetNaming {

    private val CODE_PATTERN = Regex("^[A-Z0-9][A-Z0-9._/-]{1,39}$")

    /** Kode aset dinormalkan ke huruf besar — teknisi lapangan menulisnya bebas. */
    fun code(value: String, label: String): String {
        val normalized = value.trim().uppercase()
        if (!CODE_PATTERN.matches(normalized)) {
            throw ValidationException(
                "Kode $label '$value' tidak valid: 2-40 karakter, hanya huruf/angka/titik/garis/slash",
            )
        }
        return normalized
    }

    fun name(value: String, label: String): String {
        val trimmed = value.trim()
        if (trimmed.length !in 2..150) throw ValidationException("Nama $label harus 2-150 karakter")
        return trimmed
    }

    fun address(value: String?): String? = value?.trim()?.takeIf { it.isNotEmpty() }?.also {
        if (it.length > 500) throw ValidationException("Alamat maksimal 500 karakter")
    }

    fun requiredAddress(value: String): String =
        address(value) ?: throw ValidationException("Alamat wajib diisi")

    /**
     * Catatan teknis kotak — "kunci di pos satpam", "tiang miring, pakai tangga
     * panjang", "core 5-8 disisakan untuk klaster sebelah". Lebih longgar dari
     * alamat karena isinya kalimat teknisi, bukan satu baris alamat.
     */
    fun notes(value: String?): String? = value?.trim()?.takeIf { it.isNotEmpty() }?.also {
        if (it.length > MAX_NOTES_LENGTH) {
            throw ValidationException("Catatan teknis maksimal $MAX_NOTES_LENGTH karakter")
        }
    }

    const val MAX_NOTES_LENGTH = 1_000
}

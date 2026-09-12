package com.duluin.ftth.order.application.service

import com.duluin.ftth.common.domain.error.ValidationException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * Satu rekaman CSV. [lineNumber] adalah nomor baris FISIK tempat rekaman ini MULAI di berkas
 * aslinya (header = 1) — bukan indeksnya setelah baris kosong disaring. Operator memperbaiki
 * berkasnya di Excel, dan Excel menomori dari 1; nomor yang meleset satu saja membuat pesan
 * "baris 137 salah" menunjuk orang yang salah.
 */
data class OrderImportCsvRecord(val lineNumber: Int, val values: List<String>)

/** Hasil urai berkas: header apa adanya, rekaman data, dan pemisah yang benar-benar dipakai. */
data class OrderImportCsv(
    val delimiter: Char,
    val header: List<String>,
    val records: List<OrderImportCsvRecord>,
)

/**
 * Pengurai CSV seadanya, ditulis sendiri dan SENGAJA tidak menambah dependensi baru.
 *
 * Yang dijaga di sini semuanya berasal dari satu kenyataan: berkas yang diunggah operator
 * hampir selalu hasil "Save As CSV" dari Excel, bukan CSV yang ditulis programmer.
 *
 *  1. BOM UTF-8. Excel selalu menulisnya. Tanpa dibuang, nama kolom pertama menjadi "﻿nama"
 *     dan SETIAP baris ditolak dengan "kolom tidak dikenal" — fitur yang tak pernah terpakai.
 *  2. Pemisah ';'. Excel di laptop berlokal Indonesia mengekspor dengan titik koma, bukan koma,
 *     karena koma di sana adalah pemisah desimal. Menolaknya berarti menolak berkas yang paling
 *     sering diunggah.
 *  3. CRLF. Windows menulisnya, dan '\r' yang tersisa di ujung kolom terakhir membuat kode pos
 *     "17111\r" tak pernah cocok dengan apa pun.
 *  4. Kutip RFC4180. Alamat Indonesia penuh koma ("Jl. Anggrek No. 12, RT 03/RW 05") dan tanpa
 *     dukungan kutip, satu alamat pecah menjadi dua kolom dan seluruh barisnya melenceng.
 *
 * Baris yang SELURUH kolomnya kosong dibuang diam-diam: baris kosong di ujung berkas adalah
 * bawaan hampir setiap editor, dan melaporkannya sebagai "baris ditolak" hanya membuat operator
 * mencari kesalahan yang tak ada.
 */
object OrderImportCsvParser {

    /** Kandidat pemisah, berurut prioritas saat cacah kolomnya SAMA. */
    private val DELIMITERS = listOf(',', ';', '\t')
    private const val BOM = '﻿'

    fun parse(bytes: ByteArray): OrderImportCsv {
        if (bytes.isEmpty()) throw ValidationException("Berkas CSV kosong")
        val text = decodeUtf8(bytes).removePrefix(BOM.toString())
        if (text.isBlank()) throw ValidationException("Berkas CSV kosong")
        val delimiter = detectDelimiter(text)
        val records = scan(text, delimiter)
        if (records.isEmpty()) throw ValidationException("Berkas CSV kosong")
        val header = records.first().values.map { it.trim().removePrefix(BOM.toString()) }
        if (header.all { it.isBlank() }) throw ValidationException("Baris pertama berkas harus berisi nama kolom")
        return OrderImportCsv(delimiter, header, records.drop(1))
    }

    /**
     * Decoding KETAT. Berkas yang disimpan Excel sebagai ANSI/Windows-1252 akan memicu error di
     * sini alih-alih menyelundupkan '�' ke dalam nama pelanggan — nama yang lalu tersimpan
     * rusak selamanya di basis data dan tak pernah bisa dicari lagi.
     */
    private fun decodeUtf8(bytes: ByteArray): String = runCatching {
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    }.getOrElse {
        throw ValidationException(
            "Berkas bukan UTF-8. Di Excel pilih \"Save As\" → \"CSV UTF-8 (Comma delimited)\".",
        )
    }

    /**
     * Pemisah ditebak dari BARIS HEADER saja, bukan dari seluruh berkas: alamat pelanggan penuh
     * koma dan titik koma, jadi mencacah seluruh berkas akan memenangkan pemisah yang paling
     * sering muncul di DATA, bukan yang memisahkan KOLOM. Yang menang adalah kandidat yang
     * menghasilkan kolom terbanyak di header; seri dimenangkan urutan [DELIMITERS].
     */
    private fun detectDelimiter(text: String): Char {
        val firstLine = text.substringBefore('\n').removeSuffix("\r")
        return DELIMITERS.maxByOrNull { countOutsideQuotes(firstLine, it) } ?: ','
    }

    private fun countOutsideQuotes(line: String, candidate: Char): Int {
        var quoted = false
        var count = 0
        for (ch in line) {
            when {
                ch == '"' -> quoted = !quoted
                !quoted && ch == candidate -> count++
            }
        }
        return count
    }

    /**
     * Pemindaian RFC4180 satu lewatan. Kutip hanya dianggap PEMBUKA bila ia karakter pertama
     * kolomnya; kutip di tengah kolom (mis. ukuran 5" pada catatan) diperlakukan sebagai
     * karakter biasa, sebab menganggapnya pembuka akan menelan sisa berkas ke dalam satu kolom.
     */
    @Suppress("NestedBlockDepth", "CyclomaticComplexMethod")
    private fun scan(text: String, delimiter: Char): List<OrderImportCsvRecord> {
        val records = mutableListOf<OrderImportCsvRecord>()
        val values = mutableListOf<String>()
        val field = StringBuilder()
        var quoted = false
        var quoteClosed = false
        var physicalLine = 1
        var recordLine = 1
        var index = 0

        fun endField() {
            values += field.toString()
            field.setLength(0)
            quoteClosed = false
        }

        fun endRecord() {
            endField()
            if (values.any { it.isNotBlank() }) records += OrderImportCsvRecord(recordLine, values.toList())
            values.clear()
            // Rekaman berikutnya mulai di baris fisik yang sedang berjalan — [physicalLine] sudah
            // dinaikkan pemanggil saat melewati newline-nya.
            recordLine = physicalLine
        }

        while (index < text.length) {
            val ch = text[index]
            if (quoted) {
                when {
                    ch == '"' && index + 1 < text.length && text[index + 1] == '"' -> {
                        field.append('"')
                        index++
                    }
                    ch == '"' -> {
                        quoted = false
                        quoteClosed = true
                    }
                    else -> {
                        if (ch == '\n') physicalLine++
                        field.append(ch)
                    }
                }
            } else {
                when {
                    ch == '"' && field.isEmpty() && !quoteClosed -> quoted = true
                    ch == delimiter -> endField()
                    // CR yang diikuti LF hanya dilewati; CR sendirian (Mac klasik) mengakhiri baris.
                    ch == '\r' -> if (index + 1 >= text.length || text[index + 1] != '\n') {
                        physicalLine++
                        endRecord()
                    }
                    ch == '\n' -> {
                        physicalLine++
                        endRecord()
                    }
                    else -> field.append(ch)
                }
            }
            index++
        }
        if (quoted) {
            throw ValidationException("Tanda kutip di berkas tidak pernah ditutup (mulai baris $recordLine)")
        }
        // Baris terakhir tanpa newline penutup tetap ikut; baris kosong di ujung tidak.
        if (field.isNotEmpty() || values.isNotEmpty()) endRecord()
        return records
    }
}

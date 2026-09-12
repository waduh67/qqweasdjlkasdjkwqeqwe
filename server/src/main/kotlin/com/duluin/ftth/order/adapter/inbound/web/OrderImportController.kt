package com.duluin.ftth.order.adapter.inbound.web

import com.duluin.ftth.common.domain.error.ValidationException
import com.duluin.ftth.order.application.port.inbound.OrderImportBatchDetailView
import com.duluin.ftth.order.application.port.inbound.OrderImportBatchSummaryView
import com.duluin.ftth.order.application.port.inbound.OrderImportLimits
import com.duluin.ftth.order.application.port.inbound.OrderImportUseCase
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile
import java.nio.charset.StandardCharsets
import java.util.UUID

/**
 * Impor massal pesanan / calon pelanggan dari CSV.
 *
 * Seluruh endpoint memakai SATU izin, `order.import.manage`, termasuk yang hanya membaca.
 * Praview memang tak menulis pesanan, tapi isinya adalah nama, nomor HP, dan alamat ratusan
 * orang — daftar prospek mentah yang tak kalah sensitif dari pesanannya sendiri.
 *
 * Basis path `/api/orders/import` bersanding dengan `GET /api/orders/{id}` milik
 * `OrderController`. Tidak ambigu: pembanding pola path Spring lebih memilih ruas literal
 * daripada ruas variabel, jadi `/api/orders/import` tak pernah jatuh ke `{id}` — yang
 * sebaliknya akan gagal parse UUID dan memulangkan 400 yang membingungkan.
 */
@RestController
@RequestMapping("/api/orders/import")
@Tag(name = "Order Import")
@SecurityRequirement(name = "bearer-jwt")
class OrderImportController(
    private val imports: OrderImportUseCase,
) {

    /**
     * Unggah + urai. TIDAK membuat pesanan apa pun.
     *
     * Ukuran diperiksa dari [MultipartFile.size] SEBELUM `bytes` diminta: `getBytes()` menyalin
     * seluruh berkas ke heap, dan berkas 40 MB yang salah pilih (mis. .xlsx) akan menekan heap
     * proses yang sedang melayani semua tenant lain — untuk kemudian tetap ditolak.
     */
    @PostMapping("/preview", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    @PreAuthorize("@authz.can('order.import.manage')")
    @Operation(summary = "Urai berkas CSV impor tanpa membuat pesanan")
    fun preview(@RequestPart("file") file: MultipartFile): OrderImportBatchDetailView {
        if (file.isEmpty) throw ValidationException("Berkas CSV kosong atau tidak terkirim")
        if (file.size > OrderImportLimits.MAX_FILE_BYTES) {
            throw ValidationException(
                "Ukuran berkas ${file.size / KIB} KiB melebihi batas " +
                    "${OrderImportLimits.MAX_FILE_BYTES / KIB} KiB. Pastikan yang diunggah berkas CSV, bukan Excel (.xlsx).",
            )
        }
        return imports.preview(file.originalFilename ?: file.name, file.bytes)
    }

    /** Membaca ulang praview: operator me-refresh halaman, atau kembali esok harinya. */
    @GetMapping("/{batchId}")
    @PreAuthorize("@authz.can('order.import.manage')")
    @Operation(summary = "Baca satu praview impor beserta vonis per barisnya")
    fun find(@PathVariable batchId: UUID): OrderImportBatchDetailView = imports.find(batchId)

    /** Menjalankan praview: baris ACCEPTED jadi prospek + pesanan, satu transaksi per baris. */
    @PostMapping("/{batchId}/commit")
    @PreAuthorize("@authz.can('order.import.manage')")
    @Operation(summary = "Eksekusi praview menjadi pesanan sungguhan")
    fun commit(@PathVariable batchId: UUID): OrderImportBatchDetailView = imports.commit(batchId)

    /** Riwayat impor — permukaan audit "500 pesanan ini datang dari berkas mana, diunggah siapa". */
    @GetMapping
    @PreAuthorize("@authz.can('order.import.manage')")
    @Operation(summary = "Riwayat impor terbaru")
    fun history(@RequestParam(defaultValue = "20") limit: Int): List<OrderImportBatchSummaryView> =
        imports.history(limit)

    /**
     * Berkas contoh. `text/csv` polos, BUKAN JSON berisi string: operator menekan tautan ini lalu
     * membuka hasilnya di Excel — satu langkah, tanpa menyalin-tempel apa pun.
     *
     * BOM UTF-8 ditulis di depan DENGAN SENGAJA. Tanpa BOM, Excel di Windows membaca berkas ini
     * sebagai ANSI dan "Jl. Anggrek" masih selamat, tapi begitu operator mengisinya dengan nama
     * ber-karakter non-ASCII lalu menyimpannya kembali, isinya rusak sebelum sempat diunggah.
     */
    @GetMapping("/template", produces = ["text/csv"])
    @PreAuthorize("@authz.can('order.import.manage')")
    @Operation(summary = "Unduh berkas CSV contoh")
    fun template(): ResponseEntity<ByteArray> {
        val body = BOM + imports.template()
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"contoh-impor-pesanan.csv\"")
            .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
            .body(body.toByteArray(StandardCharsets.UTF_8))
    }

    private companion object {
        const val KIB = 1024
        const val BOM = "\uFEFF"
    }
}

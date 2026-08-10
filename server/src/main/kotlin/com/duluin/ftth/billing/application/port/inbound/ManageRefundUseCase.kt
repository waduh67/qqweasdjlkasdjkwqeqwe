package com.duluin.ftth.billing.application.port.inbound

import com.duluin.ftth.billing.domain.model.RefundReason
import java.math.BigDecimal
import java.util.UUID

/**
 * Pengembalian dana atas tagihan yang sudah lunas — pelanggan salah bayar, layanan batal dipasang,
 * atau tagihan dobel. Bukan pembatalan tagihan (`void`), karena uangnya terlanjur masuk dan yang
 * harus dijelaskan bukan cuma tagihannya melainkan ke mana uang itu pergi.
 *
 * Jalannya asinkron untuk penyedia yang punya API balik (Pivot): [request] mencatat baris + mengirim
 * perintah, callback penyedia yang menutupnya lewat [ReconcileRefundUseCase]. Penyedia MANUAL tak
 * punya API — operator mentransfer sendiri lalu menutup barisnya dengan [settleManual].
 */
interface ManageRefundUseCase {

    /** Seluruh pengembalian tenant, terbaru dulu; disaring ke satu tagihan bila [invoiceId] diisi. */
    fun list(invoiceId: UUID? = null): List<RefundView>

    /** Ajukan pengembalian. Nominal null = kembalikan seluruh sisa yang masih bisa dikembalikan. */
    fun request(command: RequestRefundCommand): RefundView

    /**
     * Tutup pengembalian MANUAL secara tangan: operator sudah mentransfer balik dan menyatakannya di
     * sini. Hanya untuk baris berpenyedia MANUAL — baris Pivot ditutup callback, dan menutupnya
     * manual akan membuat catatan kita berbeda dengan mutasi penyedia.
     */
    fun settleManual(id: UUID, success: Boolean, reason: String?): RefundView
}

/** Rekonsiliasi hasil pengembalian dari callback penyedia (`REFUND.*`). */
interface ReconcileRefundUseCase {
    /**
     * Perbarui baris lewat [reference] (referensi penyedia, `data.id`) atau [clientReference]
     * (id baris kita, dikirim sebagai `clientReferenceId`). Keduanya diterima karena callback bisa
     * mendahului respons HTTP yang membawa `data.id` — saat itu barisnya baru bisa dikenali dari
     * idnya sendiri. Idempotent: callback ganda tak menjumlah pengembalian dua kali.
     */
    fun reconcile(reference: String?, clientReference: String?, success: Boolean, reason: String?)
}

/**
 * Perintah pengembalian dana. [amount] null berarti seluruh sisa tagihan (jalur paling sering:
 * "batalkan semuanya"), diisi berarti refund sebagian. [note] catatan internal operator.
 */
data class RequestRefundCommand(
    val invoiceId: UUID,
    val amount: BigDecimal? = null,
    val reason: RefundReason = RefundReason.REQUESTED_BY_CUSTOMER,
    val note: String? = null,
)

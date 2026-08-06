package com.duluin.ftth.billing.application.port.inbound

import com.duluin.ftth.billing.domain.model.InvoiceStatus
import java.util.UUID

/** Kelola tagihan milik tenant: telusur, terbitkan, batalkan, dan lihat pembayarannya. */
interface ManageInvoiceUseCase {

    /**
     * Daftar tagihan, boleh disaring per pelanggan dan/atau status. Kedua argumen
     * `null` = seluruh tagihan tenant.
     */
    fun list(customerId: UUID?, status: InvoiceStatus?): List<InvoiceView>

    fun get(id: UUID): InvoiceView

    /** Pembayaran yang tercatat atas sebuah tagihan, terbaru dulu. */
    fun payments(invoiceId: UUID): List<PaymentView>

    /** Terbitkan tagihan periode berjalan untuk tenant aktif; kembalikan jumlah yang dibuat. */
    fun generateCurrentPeriodForCurrentTenant(): Int

    fun void(id: UUID): InvoiceView

    /**
     * Buat ulang tautan bayar sebuah tagihan lewat penyedia gateway yang **aktif sekarang**,
     * lalu kembalikan proyeksinya. Dipakai saat pelanggan hendak membayar: bila penyedia
     * aktif berbeda dari penyedia tagihan, charge baru dibuat agar pembayaran mengikuti
     * setelan penyedia terbaru. Idempoten bila penyedianya sudah sama. Ditolak bila tagihan
     * sudah lunas / dibatalkan.
     */
    fun refreshPaymentLink(id: UUID): InvoiceView
}

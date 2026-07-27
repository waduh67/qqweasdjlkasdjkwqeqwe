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
}

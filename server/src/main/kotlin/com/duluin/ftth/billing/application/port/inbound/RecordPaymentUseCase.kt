package com.duluin.ftth.billing.application.port.inbound

import com.duluin.ftth.billing.application.port.outbound.PaymentSettlement
import java.util.UUID

/** Menerapkan pelunasan tagihan, baik dari settlement gateway maupun catatan manual. */
interface RecordPaymentUseCase {

    /**
     * Terapkan settlement dari gateway (dipicu webhook). Idempoten atas nomor tagihan:
     * settlement berulang untuk tagihan yang sudah lunas diabaikan.
     */
    fun applySettlement(settlement: PaymentSettlement)

    /** Catat pembayaran manual sebuah tagihan (mis. tunai/transfer diverifikasi operator). */
    fun recordManual(invoiceId: UUID, note: String?): InvoiceView
}

package com.duluin.ftth.billing.application.port.inbound

import com.duluin.ftth.billing.application.port.outbound.SimulatedChargeStatus
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
     * Buat charge in-app (mode API Pivot) untuk sebuah tagihan dengan instrumen [method]
     * (`VIRTUAL_ACCOUNT`/`QR`) + [channel] bank (wajib untuk VA), lalu kembalikan proyeksi tagihan
     * berisi instruksi bayar (nomor VA / string QRIS). Mengganti metode membuat charge baru yang
     * menimpa instruksi lama. Ditolak bila tagihan sudah lunas / dibatalkan atau gateway MANUAL.
     */
    fun chargeInvoice(id: UUID, method: String, channel: String?): InvoiceView

    /**
     * **Alat uji (sandbox saja)**: paksa sesi bayar tagihan menjadi [status] (`SUCCESS`/`EXPIRED`)
     * lewat simulasi penyedia, memakai id sesi yang sudah tersimpan dari charge terakhir. Pelunasan
     * tetap datang lewat webhook penyedia, jadi proyeksi yang dikembalikan BELUM tentu berubah
     * status — klien memuat ulang/polling. Ditolak bila penyedia bukan mode sandbox, tagihan belum
     * pernah di-charge, atau tagihan tak lagi tertunggak.
     */
    fun simulatePayment(id: UUID, status: SimulatedChargeStatus): InvoiceView
}

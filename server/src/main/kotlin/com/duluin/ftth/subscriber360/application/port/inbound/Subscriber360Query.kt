package com.duluin.ftth.subscriber360.application.port.inbound

import com.duluin.ftth.billing.BillingAccountSummary
import com.duluin.ftth.bng.SubscriberSessionRef
import com.duluin.ftth.cpe.CpeDeviceStatusRef
import com.duluin.ftth.customer.CustomerPlacement
import com.duluin.ftth.customer.CustomerRef
import com.duluin.ftth.customer.SubscriptionRef
import com.duluin.ftth.workorder.WorkOrderRef
import java.util.UUID

/**
 * Pandangan 360° satu pelanggan — satu-satunya tempat identitas pelanggan, langganan,
 * penempatan fisik, sesi PPPoE, rekening, CPE, dan work order dipertemukan.
 *
 * Modul `subscriber360` sengaja tidak punya tabel sendiri: ia menyusun jawaban dari
 * `CustomerApi`, `BngApi`, `BillingApi`, `CpeApi`, dan `WorkorderApi` (pola sama `gis`).
 * Dengan begitu pertanyaan lintas-domain "semua tentang pelanggan ini" bisa dijawab satu
 * panggilan tanpa membuat modul-modul itu saling bergantung.
 */
interface Subscriber360Query {

    /**
     * Rakit pandangan 360° pelanggan. Setiap facet lintas-modul digating izin masing-
     * masing modul: facet yang tak diizinkan bernilai null/kosong dan ditandai di
     * [Subscriber360View.access] agar UI bisa membedakan "tak boleh lihat" dari "kosong".
     *
     * @throws com.duluin.ftth.common.domain.error.NotFoundException bila pelanggan tak ada.
     */
    fun assemble(customerId: UUID): Subscriber360View
}

/**
 * Rakitan 360° pelanggan pada saat dibaca. Facet inti (identitas) selalu ada; facet
 * lintas-modul opsional bergantung izin pemanggil (lihat [access]).
 */
data class Subscriber360View(
    val customer: CustomerRef,
    /** Langganan pelanggan (tunggal); null bila tak punya ATAU izin `customer.subscription.view` tak ada. */
    val subscription: SubscriptionRef?,
    /** Penempatan ONU; null bila belum terpasang ATAU izin `customer.onu.view` tak ada. */
    val placement: CustomerPlacement?,
    /** Sesi PPPoE terkini; null bila belum diprovisi ATAU izin `bng.session.view` tak ada. */
    val session: SubscriberSessionRef?,
    /** Ringkasan rekening (tunggakan dsb.); null bila izin `billing.invoice.view` tak ada. */
    val billing: BillingAccountSummary?,
    /** Status CPE; null (bukan list kosong) bila izin `cpe.device.view` tak ada. */
    val cpeDevices: List<CpeDeviceStatusRef>?,
    /** Work order pasang-baru yang masih terbuka; null bila tak ada ATAU izin `workorder.order.view` tak ada. */
    val openWorkOrder: WorkOrderRef?,
    val access: Subscriber360Access,
)

/**
 * Facet lintas-modul mana yang boleh dilihat pemanggil. Membedakan null "ditolak" dari
 * null "memang kosong" pada [Subscriber360View] — UI menampilkan kartu terkunci vs kosong.
 */
data class Subscriber360Access(
    val subscription: Boolean,
    val placement: Boolean,
    val session: Boolean,
    val billing: Boolean,
    val cpe: Boolean,
    val workOrder: Boolean,
)

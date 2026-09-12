package com.duluin.ftth.workorder

import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * Kontrak publik module workorder untuk module lain (monitoring, saat menebak
 * pemilik sebuah ONU liar di kotak masuk provisioning).
 *
 * Sengaja tidak mengekspos agregat `WorkOrder`: pemanggil hanya perlu tahu order
 * pasang mana yang masih terbuka dan untuk pelanggan siapa, bukan lifecycle-nya.
 */
interface WorkorderApi {

    fun assignment(workOrderId: UUID, technicianId: UUID): WorkOrderAssignmentRef? = null
    fun scheduledAt(workOrderId: UUID): Instant? = null

    /**
     * PESANAN yang melahirkan work order ini; `null` bila WO-nya tidak lahir dari pesanan
     * (REPAIR dari helpdesk, DISMANTLE, preventif).
     *
     * ADA SENDIRI, TIDAK memakai [WorkOrderAssignmentRef.orderId]. Field itu — meski namanya
     * `orderId` — sebenarnya diisi `customerId` work order-nya (lihat `WorkOrderApiService.
     * assignment`), dan seluruh pemanggil yang ada sudah terlanjur menyesuaikan diri dengan
     * kenyataan itu: `FieldServiceService.create` mencocokkannya dengan `Visit.orderId`, yang
     * karenanya juga berisi id PELANGGAN. Membetulkan field itu sekarang akan menjatuhkan
     * pembuatan kunjungan di seluruh sistem; menambah jalan yang jujur di sebelahnya tidak.
     *
     * Siapa pun yang butuh id pesanan SUNGGUHAN — misalnya pemicu penanda portal saat kunjungan
     * gagal — WAJIB lewat sini. Memakai `Visit.orderId` akan mencari pesanan memakai id pelanggan
     * dan selalu tidak menemukan apa pun, tanpa error, selamanya.
     */
    fun orderIdOf(workOrderId: UUID): UUID? = null

    /**
     * WO PSB (pasang baru) yang masih terbuka, dipetakan per pelanggan yang dituju.
     *
     * Sinyal kuat untuk auto-link: ONU liar yang muncul mendadak saat ada order
     * pasang terbuka hampir pasti milik pelanggan order itu. Hanya WO yang sudah
     * menunjuk pelanggan yang disertakan; bila satu pelanggan punya beberapa order
     * terbuka, dipakai yang terjadwal paling awal.
     */
    fun openPsbByCustomer(): Map<UUID, WorkOrderRef>

    /**
     * Onboarding: buka WO PSB (pasang baru) untuk sebuah langganan lewat kontrak publik.
     * Penyelesaian WO inilah yang kelak mengaktifkan langganan (dan memprovisikan akun ke
     * RADIUS). Prioritas NORMAL — operator bisa menaikkannya lewat UI work order.
     */
    fun raisePsb(command: RaisePsbCommand): WorkOrderRef

    /**
     * Helpdesk: buka WO perbaikan (REPAIR) dari keluhan pelanggan yang butuh kunjungan
     * teknisi. Lahir tanpa roster (dispatcher yang menugaskan) dan tanpa area — WO ini
     * datang dari meja bantuan, bukan dari peta.
     */
    fun raiseRepair(command: RaiseRepairCommand): WorkOrderRef

    /**
     * Laporan kerja lapangan untuk rentang [from]..[to] (inklusif, zona server), dihitung dari WO
     * yang SELESAI di dalamnya. Dipakai modul `reporting`; workorder tetap satu-satunya yang
     * menyentuh tabel WO.
     */
    fun fieldOpsReport(from: LocalDate, to: LocalDate): FieldOpsReport
}

interface WorkOrderFulfillmentApi {
    fun validateFulfillment(command: WorkOrderFulfillmentCommand) = Unit
    fun recordFulfillmentResult(command: WorkOrderFulfillmentCommand): WorkOrderFulfillmentResult
}

data class WorkOrderFulfillmentCommand(
    val tenantId: UUID,
    val workOrderId: UUID,
    val namespace: String,
    val operationKey: String,
    val payloadHash: String,
    val source: String,
    val result: String,
)

data class WorkOrderFulfillmentResult(
    val tenantId: UUID,
    val workOrderId: UUID,
    val result: String,
    val replayed: Boolean,
)

data class WorkOrderAssignmentRef(
    val tenantId: UUID,
    val workOrderId: UUID,
    val orderId: UUID?,
    val technicianId: UUID,
    val active: Boolean,
    val areaId: UUID?,
)

/**
 * Kinerja lapangan satu tenant pada satu rentang.
 *
 * [completedCount] = WO yang tuntas di rentang; [completedByType] cacahnya per nama
 * [com.duluin.ftth.workorder.domain.model.WorkOrderType]. [avgResolutionHours] = rata-rata jam
 * dari WO dibuka sampai selesai; [avgRepairResolutionHours] = MTTR khusus perbaikan (REPAIR) —
 * angka inilah yang biasanya dijanjikan ke pelanggan, dan mencampurnya dengan PSB/preventif
 * membuatnya tak berarti. [avgResponseHours] = jeda dibuka→mulai dikerjakan (kecepatan respons
 * dispatch). Semua rata-rata `null` bila tak ada data — bukan nol, karena "tak ada WO selesai"
 * bukan berarti "selesai dalam 0 jam".
 *
 * [technicians] = produktivitas per teknisi di roster WO yang selesai, terbanyak dulu. Satu WO
 * yang dikerjakan berdua dihitung untuk KEDUANYA (tim datar, tak ada porsi kontribusi), jadi
 * jumlah kolomnya boleh melebihi [completedCount].
 */
data class FieldOpsReport(
    val completedCount: Int,
    val completedByType: Map<String, Int>,
    val avgResolutionHours: Double?,
    val avgRepairResolutionHours: Double?,
    val avgResponseHours: Double?,
    val technicians: List<TechnicianProductivity>,
)

/** Produktivitas satu teknisi: berapa WO ia tuntaskan dan rata-rata lamanya. */
data class TechnicianProductivity(
    val technicianId: UUID,
    val completedCount: Int,
    val avgResolutionHours: Double?,
)

/**
 * Perintah membuka WO PSB dari orkestrasi onboarding; selalu bertaut ke pelanggan.
 *
 * [subscriptionId] nullable sejak P5.4. Jalur onboarding ekspres memang selalu tahu langganannya
 * (ia baru saja membuatnya), tapi jalur penerimaan PESANAN tidak: pesanan dari pelanggan LAMA
 * diterima tanpa langganan baru dibuat, dan memaksa nilai palsu di sana akan menautkan WO ke
 * langganan yang bukan miliknya — lalu saga fulfillment mengaktifkan langganan yang salah.
 *
 * [orderId] menautkan WO ke pesanan asalnya. Inilah yang membuat approval WO menutup pesanannya
 * (efek `ORDER` di saga, P5.6); tanpa taut ini pesanan menggantung di status "sedang ditinjau"
 * selamanya meski pemasangannya sudah selesai.
 */
data class RaisePsbCommand(
    val customerId: UUID,
    val subscriptionId: UUID?,
    val title: String,
    val description: String?,
    val areaId: UUID?,
    val scheduledAt: Instant?,
    /** Roster teknisi awal (tim datar); kosong = WO lahir belum ditugaskan. */
    val assignees: Set<UUID> = emptySet(),
    val orderId: UUID? = null,
)

/**
 * Perintah membuka WO perbaikan dari keluhan pelanggan (module helpdesk).
 *
 * [priority] dikirim sebagai NAMA [com.duluin.ftth.workorder.domain.model.WorkOrderPriority]
 * (`LOW`/`NORMAL`/`HIGH`/`URGENT`) — konvensi yang sama dengan status langganan di
 * `CustomerApi`: enum internal tak menyeberang batas module. Nilai tak dikenal ditolak.
 */
data class RaiseRepairCommand(
    val customerId: UUID,
    val title: String,
    val description: String?,
    val priority: String = "NORMAL",
    val scheduledAt: Instant? = null,
)

/** Pandangan ringkas sebuah work order untuk konsumen lintas-module. */
data class WorkOrderRef(
    val id: UUID,
    val code: String,
    val customerId: UUID,
    val areaId: UUID?,
    val scheduledAt: Instant?,
)

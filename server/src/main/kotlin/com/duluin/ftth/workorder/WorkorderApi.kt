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

/** Perintah membuka WO PSB dari orkestrasi onboarding; selalu bertaut ke pelanggan + langganannya. */
data class RaisePsbCommand(
    val customerId: UUID,
    val subscriptionId: UUID,
    val title: String,
    val description: String?,
    val areaId: UUID?,
    val scheduledAt: Instant?,
    /** Roster teknisi awal (tim datar); kosong = WO lahir belum ditugaskan. */
    val assignees: Set<UUID> = emptySet(),
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

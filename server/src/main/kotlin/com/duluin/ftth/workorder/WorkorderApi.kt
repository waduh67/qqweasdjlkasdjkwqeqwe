package com.duluin.ftth.workorder

import java.time.Instant
import java.util.UUID

/**
 * Kontrak publik module workorder untuk module lain (monitoring, saat menebak
 * pemilik sebuah ONU liar di kotak masuk provisioning).
 *
 * Sengaja tidak mengekspos agregat `WorkOrder`: pemanggil hanya perlu tahu order
 * pasang mana yang masih terbuka dan untuk pelanggan siapa, bukan lifecycle-nya.
 */
interface WorkorderApi {

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
}

/** Perintah membuka WO PSB dari orkestrasi onboarding; selalu bertaut ke pelanggan + langganannya. */
data class RaisePsbCommand(
    val customerId: UUID,
    val subscriptionId: UUID,
    val title: String,
    val description: String?,
    val areaId: UUID?,
    val scheduledAt: Instant?,
    val assignedTo: UUID?,
)

/** Pandangan ringkas sebuah work order untuk konsumen lintas-module. */
data class WorkOrderRef(
    val id: UUID,
    val code: String,
    val customerId: UUID,
    val areaId: UUID?,
    val scheduledAt: Instant?,
)

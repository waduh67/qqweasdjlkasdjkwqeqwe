package com.duluin.ftth.helpdesk.application.port.inbound

import com.duluin.ftth.helpdesk.domain.model.TicketPriority
import com.duluin.ftth.helpdesk.domain.model.TicketStatus
import java.time.Instant
import java.util.UUID

/** Penanganan tiket oleh operator: membalas, memindahkan status, dan mengeskalasi ke work order. */
interface ManageTicketUseCase {

    /** Membalas pelanggan; tiket yang masih di antrean otomatis jadi "sedang ditangani". */
    fun reply(id: UUID, body: String): TicketDetail

    fun changeStatus(id: UUID, status: TicketStatus): TicketDetail

    /**
     * Menyerahkan tiket ke seorang operator; [userId] null mengembalikannya ke antrean bersama.
     * Tidak mengubah status — memegang tiket belum berarti mengerjakannya.
     */
    fun assign(id: UUID, userId: UUID?): TicketDetail

    /** Mengubah prioritas, sekaligus menggeser kedua tenggat SLA-nya. */
    fun changePriority(id: UUID, priority: TicketPriority): TicketDetail

    /**
     * Menerbitkan work order perbaikan dari tiket ini lalu menautkannya, sehingga pelanggan
     * melihat keluhannya benar-benar dijadwalkan. Sekali per tiket.
     */
    fun escalate(id: UUID, command: EscalateTicketCommand): TicketDetail
}

/**
 * Eskalasi ke lapangan. [note] ditambahkan ke deskripsi WO (konteks tambahan dari operator,
 * mis. hasil pengecekan jarak jauh); [scheduledAt] null = WO tanpa jadwal, diatur dispatcher.
 *
 * [priority] null = ikut prioritas tiketnya. Keluhan mendesak yang lahir jadi WO biasa adalah
 * cara paling sunyi untuk kehilangan urgensi di tengah jalan.
 */
data class EscalateTicketCommand(
    val priority: TicketPriority? = null,
    val scheduledAt: Instant? = null,
    val note: String? = null,
)

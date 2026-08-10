package com.duluin.ftth.helpdesk

import java.time.Instant
import java.util.UUID

/**
 * Diterbitkan module helpdesk saat sebuah tiket melewati janji waktunya — sekali per ronde,
 * bukan tiap penjaga menyapu. Diletakkan di base package sebagai permukaan publik helpdesk
 * supaya module lain bisa bereaksi tanpa menyentuh internalnya dan tanpa ketergantungan balik.
 *
 * Sengaja TIDAK dikirim ke pelanggan. Pelanggan tak perlu diberi tahu bahwa kita melanggar
 * janji sendiri; yang perlu tahu adalah orang yang bisa mengerjakannya. Konsumennya sisi
 * operator — hari ini pengingat di antrean & metrik, dan kotak masuk operator saat ia ada.
 *
 * @param overdueKind `RESPONSE` (belum dibalas) atau `RESOLUTION` (belum dinyatakan selesai);
 *        keduanya berarti hal berbeda bagi yang menerima — yang pertama soal kesigapan,
 *        yang kedua soal kemampuan menuntaskan.
 */
data class TicketSlaBreached(
    val tenantId: UUID,
    val ticketId: UUID,
    val code: String,
    val subject: String,
    val customerName: String,
    /** Nama `TicketPriority`, mis. "URGENT". */
    val priority: String,
    val overdueKind: String,
    val dueAt: Instant,
    /** Operator pemegang tiket saat tenggat lewat; null = tak seorang pun memegangnya. */
    val assigneeId: UUID?,
)

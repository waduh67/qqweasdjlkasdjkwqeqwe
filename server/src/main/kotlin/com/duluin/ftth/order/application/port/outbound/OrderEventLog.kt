package com.duluin.ftth.order.application.port.outbound

import com.duluin.ftth.order.OrderEvent
import java.time.Instant
import java.util.UUID

/**
 * Satu kejadian pada sebuah pesanan. Ini yang menjadi "track record": sebelum V178 tabel
 * `order_audit` ada tapi tak pernah ditulis, jadi pertanyaan "kapan pesanan ini diterima dan
 * oleh siapa?" tak punya jawaban sama sekali.
 *
 * [revision] adalah revisi agregat SETELAH kejadian, dan bersama [eventType] ia jadi kunci
 * dedup di DB — replay operation key tidak boleh melahirkan baris riwayat kedua.
 */
data class OrderAuditEntry(
    val tenantId: UUID,
    val orderId: UUID,
    val revision: Long,
    val eventType: String,
    val fromStatus: String?,
    val toStatus: String,
    val reason: String?,
    val actorId: UUID?,
    val operationNamespace: String,
    val operationKey: String,
    val payloadHash: String,
    val occurredAt: Instant,
)

interface OrderAuditStore {
    /** Idempoten menurut (tenant, order, revision, eventType). Menulis dua kali = no-op. */
    fun append(entry: OrderAuditEntry)

    /** Riwayat satu pesanan, terurut dari yang paling awal — timeline dibaca dari atas ke bawah. */
    fun timeline(tenantId: UUID, orderId: UUID): List<OrderAuditEntry>
}

/** Baris outbox yang sudah diklaim seorang worker, event-nya sudah terurai kembali. */
data class OrderOutboxDelivery(val id: UUID, val event: OrderEvent)

/**
 * Pengiriman event pesanan yang durabel. Bentuknya SENGAJA menyalin `fulfillment_outbox`
 * (lease + `FOR UPDATE SKIP LOCKED`, lihat `FulfillmentPersistence.claimPending`) supaya repo
 * ini hanya punya satu pola outbox — pola kedua berarti dua tempat yang harus diperbaiki
 * setiap kali ada bug pengiriman.
 *
 * Serialisasi payload SENGAJA disembunyikan di adapter: lapisan application tak boleh
 * bergantung pada ObjectMapper hanya untuk bisa mencatat event.
 */
interface OrderOutboxStore {
    /** WAJIB dipanggil di transaksi yang sama dengan perubahan agregat, kalau tidak event hilang saat rollback. */
    fun enqueue(event: OrderEvent)

    fun claimPending(tenantId: UUID, workerId: String, now: Instant, leaseUntil: Instant): OrderOutboxDelivery?

    fun markPublished(id: UUID, workerId: String)
}

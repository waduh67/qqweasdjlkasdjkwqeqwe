package com.duluin.ftth.order.application.service

import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.order.OrderEvent
import com.duluin.ftth.order.application.port.outbound.OrderOutboxStore
import com.duluin.ftth.tenancy.TenantApi
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.annotation.Lazy
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

/**
 * Pengirim event pesanan dari `order_outbox`.
 *
 * Polanya SENGAJA disalin dari `FulfillmentOutboxWorker` (lease 60 detik + `FOR UPDATE SKIP
 * LOCKED` di sisi SQL): dua instance aplikasi yang jalan bersamaan tidak boleh mengirim event
 * yang sama dua kali, dan instance yang mati di tengah tidak boleh mengunci barisnya selamanya.
 * Menambah pola outbox kedua di repo ini berarti dua tempat yang harus diperbaiki setiap kali
 * ada bug pengiriman.
 */
@Component
class OrderOutboxWorker(
    private val outbox: OrderOutboxStore,
    private val publisher: ApplicationEventPublisher,
    private val tenants: TenantApi? = null,
    /**
     * Rujukan ke diri sendiri lewat proxy Spring, `@Lazy` supaya bukan dependensi melingkar
     * saat bean-nya dibuat. WAJIB dipakai untuk memanggil [processNext]: panggilan langsung
     * `this.processNext(...)` melewati proxy, dan `@Transactional` di bawah ini DIAM-DIAM
     * tidak berlaku — kegagalan pengiriman tidak akan mengembalikan klaim barisnya.
     */
    @Lazy private val self: OrderOutboxWorker? = null,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${ftth.order.outbox-worker-delay:PT5S}")
    fun drain() {
        val workerId = "order-outbox-${UUID.randomUUID()}"
        tenants?.findActiveTenantIds()?.forEach { tenantId ->
            // Satu tenant yang bermasalah tidak boleh menghentikan pengiriman tenant lain.
            runCatching { drainTenant(tenantId, workerId) }
                .onFailure { log.warn("Gagal mengirim outbox pesanan tenant {}: {}", tenantId, it.message) }
        }
    }

    /**
     * Menguras satu tenant sampai antrean habis, dibatasi [MAX_BATCH] supaya satu tenant dengan
     * ribuan event tertunda tak memonopoli siklus penjadwal dan membuat tenant lain kelaparan.
     */
    fun drainTenant(tenantId: UUID, workerId: String, now: Instant = Instant.now()): Int {
        val proxied = self ?: this
        var sent = 0
        while (sent < MAX_BATCH && proxied.processNext(tenantId, workerId, now) != null) sent++
        return sent
    }

    /**
     * REQUIRES_NEW: klaim, kirim, dan tanda-terkirim harus commit sendiri. Kalau ikut transaksi
     * pemanggil, sebuah kegagalan di hilir akan mengembalikan lease dan event yang SUDAH
     * dipublikasikan akan dikirim ulang.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun processNext(tenantId: UUID, workerId: String, now: Instant = Instant.now()): OrderEvent? =
        TenantContext.runAs(tenantId) {
            val delivery = outbox.claimPending(tenantId, workerId, now, now.plusSeconds(LEASE_SECONDS))
                ?: return@runAs null
            publisher.publishEvent(delivery.event)
            outbox.markPublished(delivery.id, workerId)
            delivery.event
        }

    private companion object {
        const val LEASE_SECONDS = 60L
        const val MAX_BATCH = 100
    }
}

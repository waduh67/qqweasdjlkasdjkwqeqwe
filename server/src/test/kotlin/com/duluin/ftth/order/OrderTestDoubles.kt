package com.duluin.ftth.order

import com.duluin.ftth.common.domain.Page
import com.duluin.ftth.common.domain.PageRequest
import com.duluin.ftth.order.application.port.outbound.OrderAuditEntry
import com.duluin.ftth.order.application.port.outbound.OrderAuditStore
import com.duluin.ftth.order.application.port.outbound.OrderLeadFilter
import com.duluin.ftth.order.application.port.outbound.OrderLeadRepository
import com.duluin.ftth.order.application.port.outbound.OrderNumberGenerator
import com.duluin.ftth.order.application.port.outbound.OrderOutboxDelivery
import com.duluin.ftth.order.application.port.outbound.OrderOutboxStore
import com.duluin.ftth.order.domain.model.OrderLead
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Ganjal in-memory untuk unit test module order — TANPA Spring dan tanpa database, supaya aturan
 * bisnisnya bisa diuji tanpa menyentuh `ftth_test` yang dipakai bersama.
 */

/** Riwayat pesanan di memori; idempotensinya menirukan UNIQUE (tenant, order, revision, eventType) di V178. */
class InMemoryOrderAuditStore : OrderAuditStore {
    private val entries = mutableListOf<OrderAuditEntry>()

    override fun append(entry: OrderAuditEntry) {
        val duplicate = entries.any {
            it.tenantId == entry.tenantId && it.orderId == entry.orderId &&
                it.revision == entry.revision && it.eventType == entry.eventType
        }
        if (!duplicate) entries += entry
    }

    override fun timeline(tenantId: UUID, orderId: UUID): List<OrderAuditEntry> =
        entries.filter { it.tenantId == tenantId && it.orderId == orderId }
            .sortedWith(compareBy({ it.revision }, { it.occurredAt }))

    fun all(): List<OrderAuditEntry> = entries.toList()
}

/** Outbox di memori. Klaimnya mengabaikan lease karena tak ada worker kedua di dalam satu unit test. */
class InMemoryOrderOutboxStore : OrderOutboxStore {
    private val pending = mutableListOf<OrderOutboxDelivery>()
    private val published = mutableListOf<UUID>()

    override fun enqueue(event: OrderEvent) {
        pending += OrderOutboxDelivery(UUID.randomUUID(), event)
    }

    override fun claimPending(tenantId: UUID, workerId: String, now: Instant, leaseUntil: Instant): OrderOutboxDelivery? =
        pending.firstOrNull { it.event.tenantId == tenantId && it.id !in published }

    override fun markPublished(id: UUID, workerId: String) {
        published += id
        pending.removeIf { it.id == id }
    }

    fun enqueued(): List<OrderEvent> = pending.map { it.event }
}

/**
 * Penomoran pesanan di memori yang MENIRUKAN kontrak adapter Postgres: satu pencacah per
 * (tenant, periode) yang dinaikkan secara atomik, sehingga dua pemanggil bersamaan tak pernah
 * menerima nomor yang sama. Balapan sesungguhnya dijaga baris `order_number_counter` di DB;
 * ganjal ini menjaga BENTUK nomor dan aturan "tak boleh kembar" tetap terdokumentasi di unit test.
 */
class InMemoryOrderNumberGenerator : OrderNumberGenerator {
    private val counters = ConcurrentHashMap<Pair<UUID, String>, Int>()

    override fun next(tenantId: UUID, at: Instant): String {
        val period = PERIOD.format(at.atZone(ZONE))
        val value = counters.compute(tenantId to period) { _, previous -> (previous ?: 0) + 1 }!!
        return "ORD-$period-${value.toString().padStart(4, '0')}"
    }

    private companion object {
        val ZONE: ZoneId = ZoneId.of("Asia/Jakarta")
        val PERIOD: DateTimeFormatter = DateTimeFormatter.ofPattern("yyMM")
    }
}

/** Gudang calon pelanggan di memori; penyaringannya menirukan JPQL di adapter produksi. */
class InMemoryOrderLeadRepository : OrderLeadRepository {
    private val leads = linkedMapOf<UUID, OrderLead>()

    override fun save(lead: OrderLead) {
        leads[lead.id] = lead
    }

    override fun find(id: UUID): OrderLead? = leads[id]

    override fun search(filter: OrderLeadFilter, pageRequest: PageRequest): Page<OrderLead> {
        val matches = leads.values
            .filter { filter.status == null || it.status == filter.status }
            .filter { filter.source == null || it.source == filter.source }
            .filter {
                val q = filter.query?.trim()
                q.isNullOrBlank() || it.name.contains(q, ignoreCase = true) || it.phone.contains(q)
            }
            .sortedByDescending { it.createdAt }
        val from = (pageRequest.page * pageRequest.size).coerceAtMost(matches.size)
        val to = (from + pageRequest.size).coerceAtMost(matches.size)
        return Page(matches.subList(from, to), pageRequest.page, pageRequest.size, matches.size.toLong())
    }

    override fun findByPhone(phone: String): List<OrderLead> = leads.values.filter { it.phone == phone }
}

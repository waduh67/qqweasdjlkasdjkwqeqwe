package com.duluin.ftth.order.adapter.outbound.persistence

import com.duluin.ftth.order.OrderCreated
import com.duluin.ftth.order.OrderEvent
import com.duluin.ftth.order.OrderStateChanged
import com.duluin.ftth.order.application.port.outbound.OrderAuditEntry
import com.duluin.ftth.order.application.port.outbound.OrderAuditStore
import com.duluin.ftth.order.application.port.outbound.OrderOutboxDelivery
import com.duluin.ftth.order.application.port.outbound.OrderOutboxStore
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

/**
 * Riwayat pesanan (`order_audit`) dan pengiriman event (`order_outbox`).
 *
 * Native query lewat [EntityManager], BUKAN `JdbcTemplate`: RLS bersandar pada GUC
 * `app.tenant_id` yang dipasang pada koneksi milik session Hibernate. Koneksi lain berarti
 * GUC-nya kosong dan query mengembalikan nol baris — riwayat yang menghilang tanpa error.
 */
@Component
class OrderEventLogPersistenceAdapter(
    private val mapper: ObjectMapper,
) : OrderAuditStore, OrderOutboxStore {

    @PersistenceContext private lateinit var entityManager: EntityManager

    /**
     * `ON CONFLICT DO NOTHING` bersandar pada UNIQUE (tenant, order, revision, event_type) dari
     * V178. Jalur idempotency `order_operation` bisa mengulang efek yang sama; tanpa ini
     * timeline menampilkan kejadian kembar dan operator mengira ada dua transisi.
     */
    @Transactional
    override fun append(entry: OrderAuditEntry) {
        entityManager.createNativeQuery(
            """INSERT INTO order_audit (id, tenant_id, order_id, revision, actor_id, operation_namespace,
                   operation_key, payload_hash, event_type, payload, occurred_at, from_status, to_status, reason)
               VALUES (:id, :tenant, :order, :revision, :actor, :namespace, :operationKey, :hash, :eventType,
                   :payload, :occurredAt, :fromStatus, :toStatus, :reason)
               ON CONFLICT (tenant_id, order_id, revision, event_type) DO NOTHING""",
        ).setParameter("id", UUID.randomUUID())
            .setParameter("tenant", entry.tenantId)
            .setParameter("order", entry.orderId)
            .setParameter("revision", entry.revision)
            .setParameter("actor", entry.actorId)
            .setParameter("namespace", entry.operationNamespace)
            .setParameter("operationKey", entry.operationKey)
            .setParameter("hash", entry.payloadHash)
            .setParameter("eventType", entry.eventType)
            // `payload` tetap diisi JSON utuh: kolom terurai memenuhi kebutuhan layar hari ini,
            // JSON-nya menjaga agar kejadian lama tetap bisa dibaca ulang saat bentuknya berubah.
            .setParameter("payload", mapper.writeValueAsString(entry))
            .setParameter("occurredAt", Timestamp.from(entry.occurredAt))
            .setParameter("fromStatus", entry.fromStatus)
            .setParameter("toStatus", entry.toStatus)
            .setParameter("reason", entry.reason)
            .executeUpdate()
    }

    @Transactional(readOnly = true)
    override fun timeline(tenantId: UUID, orderId: UUID): List<OrderAuditEntry> =
        entityManager.createNativeQuery(
            """SELECT revision, event_type, from_status, to_status, reason, actor_id,
                      operation_namespace, operation_key, payload_hash, occurred_at
               FROM order_audit
               WHERE tenant_id = :tenant AND order_id = :order
               ORDER BY revision, occurred_at""",
        ).setParameter("tenant", tenantId).setParameter("order", orderId)
            .resultList.map { it as Array<*> }
            .map { row ->
                OrderAuditEntry(
                    tenantId = tenantId,
                    orderId = orderId,
                    revision = (row[0] as Number).toLong(),
                    eventType = row[1] as String,
                    fromStatus = row[2] as String?,
                    toStatus = row[3] as String,
                    reason = row[4] as String?,
                    actorId = row[5] as UUID?,
                    operationNamespace = row[6] as String,
                    operationKey = row[7] as String,
                    payloadHash = row[8] as String,
                    occurredAt = row[9].toInstantValue(),
                )
            }

    @Transactional
    override fun enqueue(event: OrderEvent) {
        entityManager.createNativeQuery(
            """INSERT INTO order_outbox (id, tenant_id, aggregate_id, event_type, revision, payload)
               VALUES (:id, :tenant, :aggregate, :eventType, :revision, :payload)
               ON CONFLICT (tenant_id, aggregate_id, event_type, revision) DO NOTHING""",
        ).setParameter("id", UUID.randomUUID())
            .setParameter("tenant", event.tenantId)
            .setParameter("aggregate", event.orderId)
            .setParameter("eventType", event.typeName())
            .setParameter("revision", event.revision)
            .setParameter("payload", mapper.writeValueAsString(event))
            .executeUpdate()
    }

    /**
     * Salinan persis pola klaim `fulfillment_outbox`: `FOR UPDATE SKIP LOCKED` supaya dua
     * instance mengambil baris berbeda tanpa saling menunggu, dan `lease_until` supaya baris
     * yang diklaim instance yang lalu mati tidak terkunci selamanya.
     */
    @Transactional
    override fun claimPending(tenantId: UUID, workerId: String, now: Instant, leaseUntil: Instant): OrderOutboxDelivery? {
        val row = entityManager.createNativeQuery(
            """WITH candidate AS (
                   SELECT id FROM order_outbox
                   WHERE tenant_id = :tenant
                     AND published_at IS NULL
                     AND (lease_until IS NULL OR lease_until <= :now)
                   ORDER BY created_at, id
                   FOR UPDATE SKIP LOCKED LIMIT 1
               )
               UPDATE order_outbox o
               SET claimed_by = :worker, lease_until = :lease, attempts = o.attempts + 1
               FROM candidate c
               WHERE o.id = c.id
               RETURNING o.id, o.event_type, o.payload""",
        ).setParameter("tenant", tenantId).setParameter("worker", workerId)
            .setParameter("now", Timestamp.from(now)).setParameter("lease", Timestamp.from(leaseUntil))
            .resultList.firstOrNull() as? Array<*> ?: return null
        return OrderOutboxDelivery(row[0] as UUID, decode(row[1] as String, row[2] as String))
    }

    @Transactional
    override fun markPublished(id: UUID, workerId: String) {
        entityManager.createNativeQuery(
            """UPDATE order_outbox SET published_at = now(), claimed_by = NULL, lease_until = NULL
               WHERE id = :id AND claimed_by = :worker""",
        ).setParameter("id", id).setParameter("worker", workerId).executeUpdate()
    }

    /**
     * Kolom `timestamptz` dikembalikan driver Postgres modern sebagai [Instant], sementara
     * versi/konfigurasi lain masih mengembalikan [Timestamp]. Cast langsung ke salah satunya
     * membuat timeline pesanan meledak `ClassCastException` begitu driver-nya berganti —
     * kegagalan yang tidak terlihat sampai dijalankan di lingkungan lain.
     */
    private fun Any?.toInstantValue(): Instant = when (this) {
        is Instant -> this
        is Timestamp -> toInstant()
        else -> error("Tipe kolom waktu tidak dikenal: ${this?.javaClass?.name}")
    }

    /**
     * Tipe disimpan sebagai kolom tersendiri, bukan diintip dari JSON: anotasi polimorfik
     * Jackson di event lintas-module akan mengunci bentuk JSON-nya, dan event yang sudah
     * terlanjur tersimpan jadi tak bisa dibaca begitu paket/nama kelasnya berpindah.
     */
    private fun decode(eventType: String, payload: String): OrderEvent = when (eventType) {
        ORDER_CREATED -> mapper.readValue(payload, OrderCreated::class.java)
        ORDER_STATE_CHANGED -> mapper.readValue(payload, OrderStateChanged::class.java)
        else -> error("Tipe event pesanan tidak dikenal: $eventType")
    }

    private fun OrderEvent.typeName(): String = when (this) {
        is OrderCreated -> ORDER_CREATED
        is OrderStateChanged -> ORDER_STATE_CHANGED
    }

    private companion object {
        const val ORDER_CREATED = "ORDER_CREATED"
        const val ORDER_STATE_CHANGED = "ORDER_STATE_CHANGED"
    }
}

package com.duluin.ftth.order.adapter.outbound.persistence

import com.duluin.ftth.common.domain.Page
import com.duluin.ftth.common.domain.PageRequest
import com.duluin.ftth.order.application.port.outbound.OrderListRow
import com.duluin.ftth.order.application.port.outbound.OrderSearchFilter
import com.duluin.ftth.order.application.port.outbound.OrderSearchPort
import com.duluin.ftth.order.domain.model.OrderStatus
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import jakarta.persistence.Query
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

/**
 * Antrean pesanan back-office.
 *
 * Native query lewat [EntityManager] (BUKAN `JdbcTemplate`): ia perlu LEFT JOIN ke `order_lead`
 * untuk membawa nama & HP pemesan dalam satu perjalanan, dan RLS menuntut koneksi yang sama
 * dengan yang sudah memasang GUC `app.tenant_id`. Koneksi lain = nol baris tanpa error.
 */
@Component
class OrderSearchPersistenceAdapter : OrderSearchPort {

    @PersistenceContext private lateinit var entityManager: EntityManager

    @Transactional(readOnly = true)
    override fun search(tenantId: UUID, filter: OrderSearchFilter, pageRequest: PageRequest): Page<OrderListRow> {
        val conditions = buildList {
            add("o.tenant_id = :tenant")
            if (filter.status != null) add("o.status = :status")
            if (filter.createdFrom != null) add("o.created_at >= :createdFrom")
            if (filter.createdTo != null) add("o.created_at <= :createdTo")
            if (!filter.query.isNullOrBlank()) {
                add(
                    """(o.order_number ILIKE :q OR o.address_text ILIKE :q
                        OR l.name ILIKE :q OR l.phone ILIKE :q)""",
                )
            }
        }
        val where = "WHERE " + conditions.joinToString(" AND ")
        val total = (
            entityManager.createNativeQuery(
                "SELECT count(*) FROM order_record o LEFT JOIN order_lead l ON l.id = o.lead_id $where",
            ).bind(tenantId, filter).singleResult as Number
            ).toLong()
        if (total == 0L) return Page(emptyList(), pageRequest.page, pageRequest.size, 0)

        val rows = entityManager.createNativeQuery(
            """SELECT o.id, o.order_number, o.status, o.customer_id, o.lead_id, l.name, l.phone,
                      o.address_text, o.city, o.appointment_starts_at, o.revision, o.created_at, o.updated_at
               FROM order_record o
               LEFT JOIN order_lead l ON l.id = o.lead_id
               $where
               ORDER BY o.created_at DESC, o.id DESC
               LIMIT :limit OFFSET :offset""",
        ).bind(tenantId, filter)
            .setParameter("limit", pageRequest.size)
            .setParameter("offset", pageRequest.page.toLong() * pageRequest.size)
            .resultList.map { it as Array<*> }
            .map { row ->
                OrderListRow(
                    id = row[0] as UUID,
                    orderNumber = row[1] as String,
                    status = OrderStatus.valueOf(row[2] as String),
                    customerId = row[3] as UUID?,
                    leadId = row[4] as UUID?,
                    leadName = row[5] as String?,
                    leadPhone = row[6] as String?,
                    address = row[7] as String,
                    city = row[8] as String,
                    appointmentStartsAt = row[9]?.toInstantValue(),
                    revision = (row[10] as Number).toLong(),
                    createdAt = row[11].toInstantValue(),
                    updatedAt = row[12].toInstantValue(),
                )
            }
        return Page(rows, pageRequest.page, pageRequest.size, total)
    }

    /**
     * Kolom `timestamptz` dikembalikan driver Postgres modern sebagai [Instant], sementara
     * versi/konfigurasi lain masih mengembalikan [Timestamp]. Cast langsung ke salah satunya
     * membuat antrean pesanan meledak `ClassCastException` begitu driver-nya berganti —
     * kegagalan yang tidak terlihat sampai dijalankan di lingkungan lain.
     */
    private fun Any?.toInstantValue(): Instant = when (this) {
        is Instant -> this
        is Timestamp -> toInstant()
        else -> error("Tipe kolom waktu tidak dikenal: ${this?.javaClass?.name}")
    }

    private fun Query.bind(tenantId: UUID, filter: OrderSearchFilter): Query {
        setParameter("tenant", tenantId)
        filter.status?.let { setParameter("status", it.name) }
        filter.createdFrom?.let { setParameter("createdFrom", Timestamp.from(it)) }
        filter.createdTo?.let { setParameter("createdTo", Timestamp.from(it)) }
        if (!filter.query.isNullOrBlank()) setParameter("q", "%${filter.query.trim()}%")
        return this
    }
}

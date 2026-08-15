package com.duluin.ftth.bng.adapter.outbound.persistence

import com.duluin.ftth.bng.application.port.outbound.AccountingRecordRepository
import com.duluin.ftth.bng.application.port.outbound.RadiusSessionRepository
import com.duluin.ftth.bng.domain.model.AccessStatus
import com.duluin.ftth.bng.domain.model.AccountingRecordPoint
import com.duluin.ftth.bng.domain.model.RadiusSession
import com.duluin.ftth.bng.domain.model.TrafficSample
import com.duluin.ftth.common.tenant.TenantContext
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import org.hibernate.Session
import org.springframework.stereotype.Component
import java.sql.Timestamp
import java.sql.Types
import java.time.Instant
import java.util.UUID

/**
 * Adapter sesi PPPoE terkini. Di-upsert per akun: satu baris hidup per
 * [RadiusSession.subscriberAccessId], jadi kalau sudah ada baris untuk akun itu
 * kolomnya diperbarui alih-alih menambah baris baru — [findBySubscriberAccessId]
 * yang dipakai listener ingest untuk memutuskannya.
 */
@Component
class RadiusSessionPersistenceAdapter(
    private val jpa: RadiusSessionJpaRepository,
) : RadiusSessionRepository {

    override fun save(session: RadiusSession): RadiusSession {
        val entity = jpa.findById(session.id).orElse(null)?.apply {
            // Identitas (subscriberAccessId, subscriptionId, customerId, username) tak disentuh.
            nasId = session.nasId
            nasIp = session.nasIp
            framedIp = session.framedIp
            sessionId = session.sessionId
            callingStationId = session.callingStationId
            online = session.online
            uptimeSeconds = session.uptimeSeconds
            startedAt = session.startedAt
            lastSeenAt = session.lastSeenAt
        } ?: RadiusSessionJpaEntity(
            id = session.id,
            subscriberAccessId = session.subscriberAccessId,
            subscriptionId = session.subscriptionId,
            customerId = session.customerId,
            username = session.username,
            nasId = session.nasId,
            nasIp = session.nasIp,
            framedIp = session.framedIp,
            sessionId = session.sessionId,
            callingStationId = session.callingStationId,
            online = session.online,
            uptimeSeconds = session.uptimeSeconds,
            startedAt = session.startedAt,
            lastSeenAt = session.lastSeenAt,
        )
        return jpa.save(entity).toDomain()
    }

    override fun findBySubscriberAccessId(subscriberAccessId: UUID): RadiusSession? =
        jpa.findBySubscriberAccessId(subscriberAccessId)?.toDomain()

    override fun findBySubscriberAccessIds(subscriberAccessIds: Collection<UUID>): Map<UUID, RadiusSession> =
        if (subscriberAccessIds.isEmpty()) emptyMap()
        else jpa.findBySubscriberAccessIdIn(subscriberAccessIds).associate { it.subscriberAccessId to it.toDomain() }

    override fun findAllForActiveAccounts(): List<RadiusSession> =
        jpa.findAllByAccountStatus(AccessStatus.ACTIVE).map { it.toDomain() }

    override fun findOnline(): List<RadiusSession> = jpa.findByOnlineTrue().map { it.toDomain() }

    private fun RadiusSessionJpaEntity.toDomain(): RadiusSession = RadiusSession.rehydrate(
        id = id,
        tenantId = tenantId ?: TenantContext.tenantId(),
        subscriberAccessId = subscriberAccessId,
        subscriptionId = subscriptionId,
        customerId = customerId,
        username = username,
        nasId = nasId,
        nasIp = nasIp,
        framedIp = framedIp,
        sessionId = sessionId,
        callingStationId = callingStationId,
        online = online,
        uptimeSeconds = uptimeSeconds,
        startedAt = startedAt,
        lastSeenAt = lastSeenAt,
    )
}

/**
 * Deret waktu akunting pada hypertable TimescaleDB — sepupu langsung
 * `OnuMetricPersistenceAdapter`, dengan alasan desain yang sama:
 *
 * - Ditulis lewat JDBC batch (`Session.doWork`) supaya GUC `app.tenant_id` ikut dan
 *   RLS menerima INSERT; DataSource langsung akan ditolak.
 * - `ON CONFLICT DO NOTHING` pada index unik `(tenant_id, subscriber_access_id, time)`
 *   membuang duplikat idempoten bila batch yang sama terkirim ulang.
 * - Laju (Mbps) DIHITUNG di SQL dari selisih penghitung kumulatif antar cuplikan
 *   berurutan (`LAG`), bukan disimpan; penghitung yang ter-reset (selisih negatif)
 *   dijadikan null agar tak muncul lonjakan palsu.
 */
@Component
class AccountingRecordPersistenceAdapter : AccountingRecordRepository {

    @PersistenceContext
    private lateinit var entityManager: EntityManager

    override fun saveAll(points: List<AccountingRecordPoint>) {
        if (points.isEmpty()) return

        entityManager.unwrap(Session::class.java).doWork { connection ->
            connection.prepareStatement(INSERT_SQL).use { statement ->
                points.forEach { point ->
                    statement.setTimestamp(1, Timestamp.from(point.time))
                    statement.setObject(2, point.tenantId)
                    statement.setObject(3, point.subscriberAccessId)
                    point.nasId?.let { statement.setObject(4, it) } ?: statement.setNull(4, Types.OTHER)
                    point.inOctets?.let { statement.setLong(5, it) } ?: statement.setNull(5, Types.BIGINT)
                    point.outOctets?.let { statement.setLong(6, it) } ?: statement.setNull(6, Types.BIGINT)
                    point.uptimeSeconds?.let { statement.setLong(7, it) } ?: statement.setNull(7, Types.BIGINT)
                    statement.addBatch()
                }
                statement.executeBatch()
            }
        }
    }

    /**
     * Tren trafik satu akun. Cuplikan mentah lebih dulu diringkas per ember `time_bucket`
     * selebar [bucketSeconds] detik (`last()` = penghitung kumulatif di ujung ember) supaya
     * rentang panjang tak mengirim puluhan ribu titik. `LAG` lalu mengambil penghitung & waktu
     * ember sebelumnya; laju = selisih_octet × 8 / selisih_detik / 1e6 (bit → Mbps). Selisih
     * waktu ≤ 0 atau selisih octet negatif (counter reset) → null. `out` = arah pelanggan
     * (Down), `in` = unggah (Up).
     */
    override fun trafficSince(subscriberAccessId: UUID, since: Instant, bucketSeconds: Long): List<TrafficSample> {
        val sql = """
            WITH bucketed AS (
                SELECT time_bucket(make_interval(secs => :bucket), time) AS bt,
                       last(out_octets, time) AS out_octets,
                       last(in_octets, time)  AS in_octets
                FROM accounting_record
                WHERE subscriber_access_id = CAST(:accessId AS uuid) AND time >= :since
                GROUP BY bt
            ),
            ordered AS (
                SELECT bt AS time,
                       out_octets,
                       in_octets,
                       lag(out_octets) OVER w AS prev_out,
                       lag(in_octets)  OVER w AS prev_in,
                       lag(bt)         OVER w AS prev_time
                FROM bucketed
                WINDOW w AS (ORDER BY bt)
            )
            SELECT time,
                   CASE WHEN prev_time IS NOT NULL
                             AND extract(epoch FROM (time - prev_time)) > 0
                             AND out_octets >= prev_out
                        THEN (out_octets - prev_out) * 8.0
                             / extract(epoch FROM (time - prev_time)) / 1000000.0
                   END AS down_mbps,
                   CASE WHEN prev_time IS NOT NULL
                             AND extract(epoch FROM (time - prev_time)) > 0
                             AND in_octets >= prev_in
                        THEN (in_octets - prev_in) * 8.0
                             / extract(epoch FROM (time - prev_time)) / 1000000.0
                   END AS up_mbps
            FROM ordered
            ORDER BY time
        """.trimIndent()

        @Suppress("UNCHECKED_CAST")
        val rows = entityManager.createNativeQuery(sql)
            .setParameter("accessId", subscriberAccessId.toString())
            .setParameter("since", Timestamp.from(since))
            .setParameter("bucket", bucketSeconds)
            .resultList as List<Array<Any?>>

        return rows.map { row ->
            TrafficSample(
                time = row[0].toInstantValue(),
                downMbps = (row[1] as? Number)?.toDouble()?.round2(),
                upMbps = (row[2] as? Number)?.toDouble()?.round2(),
            )
        }
    }

    /**
     * Total octet terpakai per akun sejak [since], sadar-reset. `LAG` mengambil penghitung
     * cuplikan sebelumnya per akun; kontribusi tiap langkah:
     *  - titik pertama akun (prev NULL) → 0 (baseline; byte sebelum [since] tak dihitung),
     *  - counter tumbuh (curr ≥ prev)   → selisih (curr − prev),
     *  - counter mundur (sesi baru)     → curr penuh (bukan selisih negatif).
     * `unggah (in) + unduh (out)` dijumlah jadi total pemakaian. `coalesce` menjaga cuplikan
     * ber-octet null tak menggagalkan agregasi.
     */
    override fun usageSince(subscriberAccessIds: Collection<UUID>, since: Instant): Map<UUID, Long> {
        if (subscriberAccessIds.isEmpty()) return emptyMap()
        val sql = """
            WITH ordered AS (
                SELECT subscriber_access_id AS access_id,
                       coalesce(in_octets, 0)  AS in_octets,
                       coalesce(out_octets, 0) AS out_octets,
                       lag(in_octets)  OVER w AS prev_in,
                       lag(out_octets) OVER w AS prev_out
                FROM accounting_record
                WHERE subscriber_access_id IN (:accessIds) AND time >= :since
                WINDOW w AS (PARTITION BY subscriber_access_id ORDER BY time)
            )
            SELECT access_id,
                   SUM(
                       CASE WHEN prev_in IS NULL THEN 0
                            WHEN in_octets >= prev_in THEN in_octets - prev_in
                            ELSE in_octets END
                     + CASE WHEN prev_out IS NULL THEN 0
                            WHEN out_octets >= prev_out THEN out_octets - prev_out
                            ELSE out_octets END
                   ) AS total_octets
            FROM ordered
            GROUP BY access_id
        """.trimIndent()

        @Suppress("UNCHECKED_CAST")
        val rows = entityManager.createNativeQuery(sql)
            .setParameter("accessIds", subscriberAccessIds)
            .setParameter("since", Timestamp.from(since))
            .resultList as List<Array<Any?>>

        return rows.associate { row -> row[0].toUuidValue() to ((row[1] as? Number)?.toLong() ?: 0L) }
    }

    private fun Any?.toInstantValue(): Instant = when (this) {
        is Instant -> this
        is Timestamp -> toInstant()
        else -> error("Tipe kolom waktu tidak dikenal: ${this?.javaClass?.name}")
    }

    private fun Any?.toUuidValue(): UUID = when (this) {
        is UUID -> this
        is String -> UUID.fromString(this)
        else -> error("Tipe kolom UUID tidak dikenal: ${this?.javaClass?.name}")
    }

    private fun Double.round2(): Double = Math.round(this * 100) / 100.0

    private companion object {
        val INSERT_SQL = """
            INSERT INTO accounting_record
                (time, tenant_id, subscriber_access_id, nas_id, in_octets, out_octets, uptime_seconds)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (tenant_id, subscriber_access_id, time) DO NOTHING
        """.trimIndent()
    }
}

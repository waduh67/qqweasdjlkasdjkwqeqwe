package com.duluin.ftth.monitoring.adapter.outbound.persistence

import com.duluin.ftth.monitoring.application.port.outbound.OnuMetricRepository
import com.duluin.ftth.monitoring.domain.model.OnuMetricPoint
import com.duluin.ftth.monitoring.domain.model.OpticalTrend
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import org.hibernate.Session
import org.springframework.stereotype.Component
import java.sql.Timestamp
import java.sql.Types
import java.time.Instant
import java.util.UUID

/**
 * Penyimpanan metrik pada hypertable TimescaleDB.
 *
 * Ditulis lewat JDBC batch, bukan JPA. Alasannya bukan sekadar kecepatan: metrik
 * bukan agregat, jadi melacaknya di persistence context Hibernate berarti
 * menyimpan ribuan objek yang tak satu pun akan diubah — memori habis tanpa
 * manfaat.
 *
 * Koneksinya diambil lewat [Session.doWork] sehingga tetap koneksi milik
 * Hibernate yang sudah membawa GUC `app.tenant_id`. Memakai `DataSource` langsung
 * akan melewatkan GUC itu dan RLS menolak seluruh INSERT — lihat
 * `TenantConnectionProvider`.
 */
@Component
class OnuMetricPersistenceAdapter : OnuMetricRepository {

    @PersistenceContext
    private lateinit var entityManager: EntityManager

    override fun saveAll(points: List<OnuMetricPoint>) {
        if (points.isEmpty()) return

        entityManager.unwrap(Session::class.java).doWork { connection ->
            connection.prepareStatement(INSERT_SQL).use { statement ->
                points.forEach { point ->
                    statement.setTimestamp(1, Timestamp.from(point.time))
                    statement.setObject(2, point.tenantId)
                    statement.setObject(3, point.onuId)
                    statement.setObject(4, point.oltId)
                    statement.setString(5, point.status)
                    statement.setNullableDouble(6, point.rxPowerDbm)
                    statement.setNullableDouble(7, point.txPowerDbm)
                    point.uptimeSeconds?.let { statement.setLong(8, it) } ?: statement.setNull(8, Types.BIGINT)
                    point.distanceMeters?.let { statement.setInt(9, it) } ?: statement.setNull(9, Types.INTEGER)
                    point.downCause?.let { statement.setString(10, it) } ?: statement.setNull(10, Types.VARCHAR)
                    point.lastOffAt?.let { statement.setTimestamp(11, Timestamp.from(it)) }
                        ?: statement.setNull(11, Types.TIMESTAMP)
                    point.lastOnAt?.let { statement.setTimestamp(12, Timestamp.from(it)) }
                        ?: statement.setNull(12, Types.TIMESTAMP)
                    statement.addBatch()
                }
                statement.executeBatch()
            }
        }
    }

    /**
     * Bacaan terbaru per ONU lewat `DISTINCT ON` — bentuk khas Postgres yang
     * memanfaatkan indeks `(onu_id, time DESC)` sehingga cukup membaca satu baris
     * per ONU alih-alih memindai seluruh riwayat.
     */
    override fun findLatestByOnuIds(onuIds: Set<UUID>): Map<UUID, OnuMetricPoint> {
        if (onuIds.isEmpty()) return emptyMap()
        val sql = """
            SELECT DISTINCT ON (onu_id)
                   time, tenant_id, onu_id, olt_id, status,
                   rx_power_dbm, tx_power_dbm, uptime_seconds, distance_meters, down_cause,
                   last_off_at, last_on_at
            FROM onu_metric
            WHERE onu_id::text = ANY(string_to_array(:onuIds, ','))
            ORDER BY onu_id, time DESC
        """.trimIndent()

        @Suppress("UNCHECKED_CAST")
        val rows = entityManager.createNativeQuery(sql)
            .setParameter("onuIds", onuIds.joinToString(",") { it.toString() })
            .resultList as List<Array<Any?>>

        return rows.map { it.toPoint() }.associateBy { it.onuId }
    }

    override fun findHistory(onuId: UUID, since: Instant, until: Instant): List<OnuMetricPoint> {
        val sql = """
            SELECT time, tenant_id, onu_id, olt_id, status,
                   rx_power_dbm, tx_power_dbm, uptime_seconds, distance_meters, down_cause,
                   last_off_at, last_on_at
            FROM onu_metric
            WHERE onu_id = CAST(:onuId AS uuid) AND time >= :since AND time <= :until
            ORDER BY time
        """.trimIndent()

        @Suppress("UNCHECKED_CAST")
        val rows = entityManager.createNativeQuery(sql)
            .setParameter("onuId", onuId.toString())
            .setParameter("since", Timestamp.from(since))
            .setParameter("until", Timestamp.from(until))
            .resultList as List<Array<Any?>>

        return rows.map { it.toPoint() }
    }

    /**
     * Kecenderungan redaman lewat regresi linear di database (`regr_slope`).
     *
     * Menghitungnya di SQL, bukan di JVM, menghindari menarik ribuan titik
     * pengukuran hanya untuk menghasilkan satu angka. Kemiringannya per detik lalu
     * dikonversi ke per hari agar terbaca manusia.
     */
    override fun computeTrend(onuId: UUID, since: Instant): OpticalTrend? {
        val sql = """
            SELECT count(rx_power_dbm)                                          AS samples,
                   avg(rx_power_dbm)                                            AS avg_rx,
                   min(rx_power_dbm)                                            AS min_rx,
                   max(rx_power_dbm)                                            AS max_rx,
                   regr_slope(rx_power_dbm, extract(epoch from time))           AS slope_per_second
            FROM onu_metric
            WHERE onu_id = CAST(:onuId AS uuid) AND time >= :since AND rx_power_dbm IS NOT NULL
        """.trimIndent()

        @Suppress("UNCHECKED_CAST")
        val row = (
            entityManager.createNativeQuery(sql)
                .setParameter("onuId", onuId.toString())
                .setParameter("since", Timestamp.from(since))
                .resultList as List<Array<Any?>>
            ).firstOrNull() ?: return null

        val samples = (row[0] as? Number)?.toInt() ?: 0
        if (samples == 0) return null

        return OpticalTrend(
            onuId = onuId,
            samples = samples,
            averageRxPowerDbm = (row[1] as? Number)?.toDouble()?.round2(),
            minRxPowerDbm = (row[2] as? Number)?.toDouble()?.round2(),
            maxRxPowerDbm = (row[3] as? Number)?.toDouble()?.round2(),
            // Kemiringan butuh minimal dua titik; di bawah itu Postgres mengembalikan null.
            trendDbPerDay = (row[4] as? Number)?.toDouble()?.times(SECONDS_PER_DAY)?.round2(),
        )
    }

    /**
     * Pemindaian massal ONU yang redamannya memburuk, satu query untuk semua ONU
     * tenant. Regresi & filternya dilakukan di database: menariknya ke JVM berarti
     * memuat seluruh riwayat ribuan ONU hanya untuk membuang hampir semuanya.
     *
     * `regr_slope` diulang di HAVING karena SQL tidak mengizinkan alias SELECT di
     * klausa itu. Kemiringan per detik dikali sehari lalu dibandingkan dengan
     * ambang negatif: memburuk = turun lebih curam dari -[thresholdDbPerDay].
     */
    override fun findDegrading(since: Instant, minSamples: Int, thresholdDbPerDay: Double): List<OpticalTrend> {
        val sql = """
            SELECT onu_id,
                   count(rx_power_dbm)                                          AS samples,
                   avg(rx_power_dbm)                                            AS avg_rx,
                   min(rx_power_dbm)                                            AS min_rx,
                   max(rx_power_dbm)                                            AS max_rx,
                   regr_slope(rx_power_dbm, extract(epoch from time))           AS slope_per_second
            FROM onu_metric
            WHERE time >= :since AND rx_power_dbm IS NOT NULL
            GROUP BY onu_id
            HAVING count(rx_power_dbm) >= :minSamples
               AND regr_slope(rx_power_dbm, extract(epoch from time)) * :secondsPerDay <= :maxSlopePerDay
        """.trimIndent()

        @Suppress("UNCHECKED_CAST")
        val rows = entityManager.createNativeQuery(sql)
            .setParameter("since", Timestamp.from(since))
            .setParameter("minSamples", minSamples)
            .setParameter("secondsPerDay", SECONDS_PER_DAY)
            // Ambang praktis dalam bentuk negatif: kemiringan harus lebih curam turun dari ini.
            .setParameter("maxSlopePerDay", -thresholdDbPerDay)
            .resultList as List<Array<Any?>>

        return rows.map { row ->
            OpticalTrend(
                onuId = row[0] as UUID,
                samples = (row[1] as? Number)?.toInt() ?: 0,
                averageRxPowerDbm = (row[2] as? Number)?.toDouble()?.round2(),
                minRxPowerDbm = (row[3] as? Number)?.toDouble()?.round2(),
                maxRxPowerDbm = (row[4] as? Number)?.toDouble()?.round2(),
                trendDbPerDay = (row[5] as? Number)?.toDouble()?.times(SECONDS_PER_DAY)?.round2(),
            )
        }
    }

    override fun countSince(since: Instant): Long {
        val result = entityManager
            .createNativeQuery("SELECT count(*) FROM onu_metric WHERE time >= :since")
            .setParameter("since", Timestamp.from(since))
            .singleResult
        return (result as? Number)?.toLong() ?: 0
    }

    /**
     * Kolom `timestamptz` dikembalikan driver Postgres modern sebagai [Instant],
     * tetapi versi/konfigurasi lain masih mengembalikan [Timestamp]. Keduanya
     * diterima agar pemetaan tidak bergantung pada detail driver.
     */
    private fun Any?.toInstantValue(): Instant = when (this) {
        is Instant -> this
        is Timestamp -> toInstant()
        else -> error("Tipe kolom waktu tidak dikenal: ${this?.javaClass?.name}")
    }

    private fun Array<Any?>.toPoint() = OnuMetricPoint(
        time = this[0].toInstantValue(),
        tenantId = this[1] as UUID,
        onuId = this[2] as UUID,
        oltId = this[3] as UUID?,
        status = this[4] as String,
        rxPowerDbm = (this[5] as? Number)?.toDouble(),
        txPowerDbm = (this[6] as? Number)?.toDouble(),
        uptimeSeconds = (this[7] as? Number)?.toLong(),
        distanceMeters = (this[8] as? Number)?.toInt(),
        downCause = this[9] as String?,
        lastOffAt = this[10].toInstantValueOrNull(),
        lastOnAt = this[11].toInstantValueOrNull(),
    )

    private fun Any?.toInstantValueOrNull(): Instant? = this?.toInstantValue()

    private fun java.sql.PreparedStatement.setNullableDouble(index: Int, value: Double?) {
        if (value == null) setNull(index, Types.DOUBLE) else setDouble(index, value)
    }

    private fun Double.round2(): Double = Math.round(this * 100) / 100.0

    private companion object {
        const val SECONDS_PER_DAY = 86_400.0

        val INSERT_SQL = """
            INSERT INTO onu_metric
                (time, tenant_id, onu_id, olt_id, status, rx_power_dbm, tx_power_dbm, uptime_seconds,
                 distance_meters, down_cause, last_off_at, last_on_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """.trimIndent()
    }
}

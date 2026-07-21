package com.duluin.ftth.monitoring.application.port.outbound

import com.duluin.ftth.common.domain.Page
import com.duluin.ftth.common.domain.PageRequest
import com.duluin.ftth.monitoring.domain.model.Alarm
import com.duluin.ftth.monitoring.domain.model.AlarmKind
import com.duluin.ftth.monitoring.domain.model.AlarmRule
import com.duluin.ftth.monitoring.domain.model.AlarmStatus
import com.duluin.ftth.monitoring.domain.model.Collector
import com.duluin.ftth.monitoring.domain.model.OnuMetricPoint
import com.duluin.ftth.monitoring.domain.model.OpticalTrend
import java.time.Instant
import java.util.UUID

/**
 * Port keluar module monitoring. Dikumpulkan dalam satu berkas karena semuanya
 * deklarasi murni dan saling terkait erat dalam satu alur: collector → batch →
 * metrik → alarm.
 */

interface CollectorRepository {

    fun save(collector: Collector): Collector

    fun findById(id: UUID): Collector?

    /**
     * Pencarian untuk autentikasi, dilakukan SEBELUM tenant diketahui — karena itu
     * tabel `collector` sengaja tanpa RLS, sama seperti `refresh_token`.
     */
    fun findByApiKeyHash(apiKeyHash: String): Collector?

    fun findAllByTenant(tenantId: UUID): List<Collector>

    /** Seluruh collector aktif lintas tenant — dipakai penjaga collector membisu. */
    fun findAllActive(): List<Collector>

    fun existsByName(tenantId: UUID, name: String): Boolean

    fun deleteById(id: UUID)

    /** OLT yang ditugaskan ke collector ini. Kosong berarti "semua OLT tenant". */
    fun findAssignedOltIds(collectorId: UUID): Set<UUID>

    fun replaceAssignedOltIds(collectorId: UUID, oltIds: Set<UUID>)
}

interface IngestBatchRepository {

    /**
     * Mencatat batch bila belum pernah diterima.
     *
     * @return true bila ini kiriman baru, false bila duplikat. Menggabungkan cek
     *         dan tulis dalam satu operasi supaya dua kiriman ulang yang tiba
     *         bersamaan tidak lolos berdua.
     */
    fun registerIfNew(batchId: String, collectorId: UUID, tenantId: UUID, readingCount: Int): Boolean

    /** Membuang catatan lama; jendela dedup tidak perlu lebih panjang dari beberapa jam. */
    fun deleteOlderThan(cutoff: Instant): Int
}

interface OnuMetricRepository {

    /** Penulisan massal satu siklus polling. */
    fun saveAll(points: List<OnuMetricPoint>)

    fun findLatestByOnuIds(onuIds: Set<UUID>): Map<UUID, OnuMetricPoint>

    fun findHistory(onuId: UUID, since: Instant, until: Instant): List<OnuMetricPoint>

    fun computeTrend(onuId: UUID, since: Instant): OpticalTrend?

    fun countSince(since: Instant): Long
}

interface AlarmRepository {

    fun save(alarm: Alarm): Alarm

    fun findById(id: UUID): Alarm?

    /** Alarm terbuka untuk sebuah entitas & jenis — dasar peredaman banjir alarm. */
    fun findOpen(kind: AlarmKind, entityId: UUID): Alarm?

    fun findAllOpenByKind(kind: AlarmKind): List<Alarm>

    /** Semua alarm yang belum ditutup (ACTIVE atau ACKNOWLEDGED), lintas jenis. */
    fun findAllOpen(): List<Alarm>

    fun search(status: AlarmStatus?, kind: AlarmKind?, pageRequest: PageRequest): Page<Alarm>

    fun countByStatus(): Map<AlarmStatus, Long>
}

interface AlarmRuleRepository {

    fun save(rule: AlarmRule): AlarmRule

    fun findByKind(kind: AlarmKind): AlarmRule?

    fun findAll(): List<AlarmRule>
}

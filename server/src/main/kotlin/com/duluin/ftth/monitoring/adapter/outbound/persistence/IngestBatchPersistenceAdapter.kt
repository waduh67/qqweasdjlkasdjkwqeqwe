package com.duluin.ftth.monitoring.adapter.outbound.persistence

import com.duluin.ftth.monitoring.application.port.outbound.IngestBatchRepository
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import org.springframework.stereotype.Component
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

/**
 * Deduplikasi kiriman collector.
 *
 * Memakai `ON CONFLICT DO NOTHING` sehingga pemeriksaan dan penulisan menjadi
 * satu operasi atomik. Kalau dipecah menjadi SELECT lalu INSERT, dua kiriman
 * ulang yang tiba bersamaan bisa sama-sama lolos pemeriksaan dan metriknya
 * tersimpan dua kali — persis yang hendak dicegah.
 */
@Component
class IngestBatchPersistenceAdapter : IngestBatchRepository {

    @PersistenceContext
    private lateinit var entityManager: EntityManager

    override fun registerIfNew(batchId: String, collectorId: UUID, tenantId: UUID, readingCount: Int): Boolean {
        val inserted = entityManager.createNativeQuery(
            """
            INSERT INTO ingest_batch (batch_id, collector_id, tenant_id, reading_count)
            VALUES (:batchId, CAST(:collectorId AS uuid), CAST(:tenantId AS uuid), :readingCount)
            ON CONFLICT (batch_id) DO NOTHING
            """.trimIndent(),
        )
            .setParameter("batchId", batchId)
            .setParameter("collectorId", collectorId.toString())
            .setParameter("tenantId", tenantId.toString())
            .setParameter("readingCount", readingCount)
            .executeUpdate()

        return inserted > 0
    }

    override fun deleteOlderThan(cutoff: Instant): Int =
        entityManager.createNativeQuery("DELETE FROM ingest_batch WHERE received_at < :cutoff")
            .setParameter("cutoff", Timestamp.from(cutoff))
            .executeUpdate()
}

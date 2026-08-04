package com.duluin.ftth.platformbilling.adapter.outbound.persistence

import com.duluin.ftth.platformbilling.application.port.outbound.SubscriptionUsageProbe
import com.duluin.ftth.platformbilling.application.port.outbound.UsageCount
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/**
 * Menghitung pemakaian tenant konteks berjalan lewat [EntityManager] (koneksi Hibernate) sehingga
 * GUC `app.tenant_id` terpasang → RLS otomatis membatasi hitungan ke tenant aktif. Angka bersifat
 * kosmetik (tak ada batas nyata), jadi tabel yang tak ada/pun galat dihitung 0 dengan aman.
 */
@Component
class SubscriptionUsageJpaAdapter(
    @PersistenceContext private val entityManager: EntityManager,
) : SubscriptionUsageProbe {

    @Transactional(readOnly = true)
    override fun currentTenantUsage(): List<UsageCount> = METRICS.map { (key, label, table) ->
        UsageCount(key, label, count(table))
    }

    private fun count(table: String): Long =
        runCatching {
            (entityManager.createNativeQuery("SELECT count(*) FROM $table").singleResult as Number).toLong()
        }.getOrDefault(0L)

    private companion object {
        // key, label tampil, nama tabel (RLS-aware).
        val METRICS = listOf(
            Triple("olt", "OLT", "olt"),
            Triple("odc", "ODC", "odc"),
            Triple("odp", "ODP", "odp"),
            Triple("customer", "Pelanggan", "customer"),
        )
    }
}

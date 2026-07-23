package com.duluin.ftth.incident.application.service

import com.duluin.ftth.incident.application.port.outbound.IncidentRepository
import com.duluin.ftth.incident.domain.model.Incident
import com.duluin.ftth.incident.domain.model.IncidentRootType
import com.duluin.ftth.incident.domain.model.IncidentSeverity
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

/**
 * Mendamaikan hasil korelasi terkini dengan insiden yang tersimpan.
 *
 * Berpola sama dengan mesin alarm yang menutup sendiri: akar yang masih beralarm
 * memperbarui insiden yang sudah ada (atau membuka yang baru), dan akar yang tak
 * lagi muncul di hasil korelasi berarti pulih — insidennya ditutup otomatis.
 * Idempotent: aman dipanggil berulang kali untuk keadaan yang sama.
 */
@Service
class IncidentReconciler(
    private val correlation: IncidentCorrelationService,
    private val repository: IncidentRepository,
) {
    /**
     * REQUIRES_NEW karena dipanggil dari listener AFTER_COMMIT: transaksi penerbit
     * sudah selesai, jadi korelasi harus berjalan di transaksinya sendiri yang
     * benar-benar di-commit — persis pola AuditWriter.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun reconcile(tenantId: UUID) {
        val groups = correlation.correlate()
        val open = repository.findOpen()
        val openByKey = open.associateBy { "${it.rootType}:${it.rootId}" }
        val now = Instant.now()
        val seen = HashSet<String>()

        groups.forEach { g ->
            seen += g.key
            val existing = openByKey[g.key]
            if (existing != null) {
                existing.refresh(
                    IncidentSeverity.valueOf(g.severity),
                    g.title,
                    g.rootLabel,
                    g.alarmCount,
                    g.affectedCustomerCount,
                    now,
                )
                repository.save(existing)
            } else {
                repository.save(
                    Incident.open(
                        tenantId = tenantId,
                        rootType = IncidentRootType.valueOf(g.rootType),
                        rootId = g.rootId,
                        rootLabel = g.rootLabel,
                        severity = IncidentSeverity.valueOf(g.severity),
                        title = g.title,
                        alarmCount = g.alarmCount,
                        affectedCustomerCount = g.affectedCustomerCount,
                        at = now,
                    ),
                )
            }
        }

        // Akar yang tak lagi muncul di korelasi berarti pulih → tutup otomatis.
        open.filter { "${it.rootType}:${it.rootId}" !in seen }.forEach {
            it.resolve(now, auto = true)
            repository.save(it)
        }
    }
}

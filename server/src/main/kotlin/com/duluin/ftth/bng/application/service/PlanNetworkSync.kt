package com.duluin.ftth.bng.application.service

import com.duluin.ftth.bng.application.port.outbound.SubscriberAccessRepository
import com.duluin.ftth.bng.domain.model.AccessStatus
import com.duluin.ftth.catalog.CatalogApi
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Menegakkan perubahan atribut jaringan paket ke RADIUS — inti nilai "RADIUS jadi pusat":
 * ubah kecepatan paket sekali, seluruh akunnya ikut tanpa menyentuh router.
 *
 * Dipicu event [com.duluin.ftth.catalog.PlanUpdated] lewat [PlanSyncListener]. Dipisah
 * dari listener agar punya batas transaksinya sendiri (REQUIRES_NEW): listener berjalan
 * pada fase AFTER_COMMIT transaksi katalog yang sudah selesai, jadi tanpa transaksi baru
 * tulisan antrean di sini takkan ter-commit. Tak mengaudit — perubahan paket sudah
 * teraudit di modul catalog.
 */
@Service
@Transactional(propagation = Propagation.REQUIRES_NEW)
class PlanNetworkSync(
    private val subscriberAccessRepository: SubscriberAccessRepository,
    private val catalogApi: CatalogApi,
    private val bngActions: BngActionService,
) {
    /**
     * Sinkronkan ulang grup RADIUS paket ke tiap BRAS yang menaunginya, lalu dorong CoA
     * ke sesi akun aktif agar kecepatan baru langsung berlaku. No-op bila paket telah
     * terhapus dari katalog atau tak ada akun (di BRAS) yang memakainya.
     */
    fun resync(planId: UUID) {
        val plan = catalogApi.findPlanNetwork(planId) ?: return
        val onNas = subscriberAccessRepository.findByPlanId(planId)
            .filter { it.status != AccessStatus.TERMINATED && it.nasId != null }
        if (onNas.isEmpty()) return

        // Satu SYNC_GROUP per BRAS berbeda — grupnya ada di DB RADIUS masing-masing BRAS.
        onNas.distinctBy { it.nasId }.forEach {
            bngActions.enqueueSyncGroup(it.nasId!!, it.tenantId, plan, requestedBy = null, requestedByEmail = null)
        }
        // CoA hanya akun aktif; akun terisolir memang tak seharusnya cepat.
        onNas.filter { it.status == AccessStatus.ACTIVE }.forEach {
            bngActions.enqueueCoa(it, plan.downMbps, plan.upMbps, requestedBy = null, requestedByEmail = null)
        }
    }
}

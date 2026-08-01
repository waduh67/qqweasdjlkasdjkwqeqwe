package com.duluin.ftth.monitoring.application.service

import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.network.OltDeletedEvent
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener

/**
 * Membersihkan kotak masuk provisioning saat sebuah OLT dihapus dari inventory.
 *
 * `discovered_onu` merujuk OLT sebagai uuid polos tanpa foreign key lintas-module,
 * jadi menghapus OLT tak ikut menghapus ONU liar yang pernah dilaporkannya —
 * baris-baris itu akan menggantung sebagai yatim yang takkan pernah hilang sendiri
 * (OLT-nya sudah tiada, tak ada lagi yang mem-poll untuk memperbaruinya).
 *
 * Berjalan pada fase AFTER_COMMIT: baru bersih-bersih setelah penghapusan OLT
 * benar-benar ter-commit, agar rollback tak ikut membuang kotak masuk. Tenant
 * context dipasang dari event — bukan thread saat ini — supaya method transaksional
 * yang dipanggil di dalamnya membuka koneksi dengan GUC `app.tenant_id` yang benar
 * (RLS). Pola ini menyengaja: `@Transactional` TIDAK ditaruh pada listener ini,
 * sebab transaksi (dan pemasangan GUC) akan terlanjur dibuka sebelum tenant di-set;
 * batas transaksinya ada di [DiscoveredOnuService.purgeForDeletedOlt] yang dipanggil
 * di dalam [TenantContext.runAs]. Kegagalan bersih-bersih tak boleh menggagalkan
 * penghapusan OLT.
 */
@Component
class OltDeletedListener(
    private val discoveredOnu: DiscoveredOnuService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    fun on(event: OltDeletedEvent) {
        try {
            TenantContext.runAs(event.tenantId) {
                val removed = discoveredOnu.purgeForDeletedOlt(event.oltId)
                if (removed > 0) {
                    log.info("Membersihkan {} ONU terdeteksi yatim milik OLT {} yang dihapus", removed, event.oltCode)
                }
            }
        } catch (ex: Exception) {
            log.warn("Gagal membersihkan ONU terdeteksi milik OLT {} yang dihapus", event.oltCode, ex)
        }
    }
}

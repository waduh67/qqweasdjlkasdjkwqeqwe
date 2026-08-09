package com.duluin.ftth.portal.application.service

import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.customer.CustomerContactChanged
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener

/**
 * Menautkan perubahan kontak pelanggan (module customer) ke indeks identitas portal.
 *
 * AFTER_COMMIT: indeks hanya menyusul kontak yang benar-benar tersimpan. `fallbackExecution`
 * agar event yang terbit di luar transaksi tetap diproses. Kegagalan sinkronisasi di-log dan
 * TIDAK menggagalkan penyimpanan pelanggan — operator sedang mengurus data pelanggan, bukan
 * portal, dan kehilangan satu jalur masuk lebih ringan daripada kehilangan penyuntingannya.
 * Jalur masuk lewat username tetap utuh, dan penyimpanan berikutnya memperbaiki indeksnya.
 */
@Component
class PortalCustomerContactListener(
    private val identitySync: PortalIdentitySyncService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    fun on(event: CustomerContactChanged) {
        try {
            TenantContext.runAs(event.tenantId) { identitySync.sync(event.customerId) }
        } catch (ex: Exception) {
            log.warn("Sinkronisasi identitas portal gagal untuk pelanggan {}", event.customerId, ex)
        }
    }
}

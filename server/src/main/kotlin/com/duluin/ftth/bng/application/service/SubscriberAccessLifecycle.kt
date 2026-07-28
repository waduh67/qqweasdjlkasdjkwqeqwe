package com.duluin.ftth.bng.application.service

import com.duluin.ftth.bng.application.port.outbound.SubscriberAccessRepository
import com.duluin.ftth.bng.domain.model.AccessStatus
import com.duluin.ftth.bng.domain.model.SubscriberAccess
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Menyelaraskan status akun jaringan dengan status langganan.
 *
 * Dipisah dari [SubscriberAccessService] karena ini jalur yang digerakkan event
 * (bukan pengguna): ia butuh batas transaksinya sendiri saat dipanggil listener
 * pasca-commit, dan tidak mengaudit (peristiwa langganan yang memicunya sudah
 * teraudit di module customer). Akun yang sudah dihentikan dibiarkan — statusnya
 * terminal. Untuk slice fondasi ini belum ada efek jaringan; hanya status data.
 *
 * REQUIRES_NEW: listener berjalan pada fase AFTER_COMMIT saat transaksi langganan
 * sudah selesai — tanpa transaksi baru, tulisan di sini takkan pernah ter-commit.
 *
 * Isolir dari sini "beneran motong" juga: selain mengubah status, ia mengantre
 * DISCONNECT lewat [BngActionService] (pelaku null = dipicu sistem, bukan operator),
 * sama seperti tombol Isolir di UI.
 */
@Service
@Transactional(propagation = Propagation.REQUIRES_NEW)
class SubscriberAccessLifecycle(
    private val subscriberAccessRepository: SubscriberAccessRepository,
    private val bngActions: BngActionService,
) {
    fun onActivated(subscriptionId: UUID) = forEachLive(subscriptionId) { it.activate() }

    fun onIsolated(subscriptionId: UUID) = forEachLive(subscriptionId) {
        it.isolate()
        bngActions.enqueueDisconnect(it, requestedBy = null, requestedByEmail = null)
    }

    fun onTerminated(subscriptionId: UUID) =
        subscriberAccessRepository.findBySubscriptionId(subscriptionId).forEach {
            it.terminate()
            subscriberAccessRepository.save(it)
            // Langganan berakhir → cabut otorisasi akun dari RADIUS (system-triggered,
            // pelaku null). No-op bila akun tak pernah ditugaskan ke BRAS.
            bngActions.enqueueDeprovision(it, requestedBy = null, requestedByEmail = null)
        }

    private inline fun forEachLive(subscriptionId: UUID, change: (SubscriberAccess) -> Unit) {
        subscriberAccessRepository.findBySubscriptionId(subscriptionId)
            .filter { it.status != AccessStatus.TERMINATED }
            .forEach {
                change(it)
                subscriberAccessRepository.save(it)
            }
    }
}

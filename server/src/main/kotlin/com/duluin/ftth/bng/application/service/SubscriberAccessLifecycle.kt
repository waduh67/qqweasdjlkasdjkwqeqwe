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
 */
@Service
@Transactional(propagation = Propagation.REQUIRES_NEW)
class SubscriberAccessLifecycle(
    private val subscriberAccessRepository: SubscriberAccessRepository,
) {
    fun onActivated(subscriptionId: UUID) = forEachLive(subscriptionId) { it.activate() }

    fun onIsolated(subscriptionId: UUID) = forEachLive(subscriptionId) { it.isolate() }

    fun onTerminated(subscriptionId: UUID) =
        subscriberAccessRepository.findBySubscriptionId(subscriptionId).forEach {
            it.terminate()
            subscriberAccessRepository.save(it)
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

package com.duluin.ftth.bng.application.service

import com.duluin.ftth.bng.application.port.outbound.SubscriberAccessRepository
import com.duluin.ftth.bng.domain.model.AccessStatus
import com.duluin.ftth.bng.domain.model.SubscriberAccess
import com.duluin.ftth.catalog.CatalogApi
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
 * terminal.
 *
 * REQUIRES_NEW: listener berjalan pada fase AFTER_COMMIT saat transaksi langganan
 * sudah selesai — tanpa transaksi baru, tulisan di sini takkan pernah ter-commit.
 *
 * Efek jaringan ikut digerakkan di sini: aktivasi memprovisikan akun yang baru pertama
 * kali online ke RADIUS, isolir "beneran motong" (antre DISCONNECT), terminasi mencabut
 * otorisasi (antre DEPROVISION) — semuanya lewat [BngActionService] dengan pelaku null
 * (dipicu sistem, bukan operator), sama seperti tombol padanannya di UI.
 */
@Service
@Transactional(propagation = Propagation.REQUIRES_NEW)
class SubscriberAccessLifecycle(
    private val subscriberAccessRepository: SubscriberAccessRepository,
    private val catalogApi: CatalogApi,
    private val bngActions: BngActionService,
) {
    /**
     * Langganan aktif → akun disinkronkan ke ACTIVE. Akun yang tadinya PENDING belum pernah
     * ditulis ke RADIUS (dibuat saat langganan masih menunggu instalasi); aktivasi (WO PSB
     * selesai) = saat pelanggan resmi online, jadi grup paket dipastikan ada lalu kredensial +
     * keanggotaan ditulis. Akun yang tadinya ISOLATED (pulih billing) sudah punya baris RADIUS →
     * cukup sinkron status; sesi berikutnya re-auth mengambil profil aktif (sama seperti tombol Pulihkan).
     */
    fun onActivated(subscriptionId: UUID) =
        subscriberAccessRepository.findBySubscriptionId(subscriptionId)
            .filter { it.status != AccessStatus.TERMINATED }
            .forEach { access ->
                val wasPending = access.status == AccessStatus.PENDING
                access.activate()
                subscriberAccessRepository.save(access)
                if (wasPending) enqueueProvisioning(access)
            }

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

    /**
     * Provisikan akun yang baru pertama kali online (PENDING→ACTIVE): pastikan grup paket ada di
     * BRAS lalu tulis kredensial + keanggotaan. Nilai jaringan (rate-limit) dibaca live dari katalog.
     * No-op bila akun belum ditugaskan ke BRAS atau paketnya tak ditemukan.
     */
    private fun enqueueProvisioning(access: SubscriberAccess) {
        val nasId = access.nasId ?: return
        val plan = catalogApi.findPlanNetwork(access.planId) ?: return
        bngActions.enqueueSyncGroup(nasId, access.tenantId, plan, requestedBy = null, requestedByEmail = null)
        bngActions.enqueueProvision(access, requestedBy = null, requestedByEmail = null)
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

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
 * kali online ke RADIUS (atau memulihkan yang tadinya terisolir), isolir memindahkannya ke
 * grup isolir lalu memutus sesinya, terminasi mencabut otorisasi (antre DEPROVISION) —
 * semuanya lewat [BngActionService] dengan pelaku null (dipicu sistem, bukan operator),
 * sama seperti tombol padanannya di UI.
 */
@Service
@Transactional(propagation = Propagation.REQUIRES_NEW)
class SubscriberAccessLifecycle(
    private val subscriberAccessRepository: SubscriberAccessRepository,
    private val catalogApi: CatalogApi,
    private val bngActions: BngActionService,
) {
    /**
     * Langganan aktif → akun disinkronkan ke ACTIVE, dengan dua jalan berbeda menuju online:
     *  - tadinya PENDING: belum pernah ditulis ke RADIUS (akun dibuat saat langganan masih
     *    menunggu instalasi). Aktivasi (WO PSB selesai) = saat pelanggan resmi online, jadi grup
     *    paket dipastikan ada lalu kredensial + keanggotaan ditulis.
     *  - tadinya ISOLATED (pembayaran masuk): baris RADIUS-nya sudah ada tapi menunjuk grup
     *    isolir. Harus dipulihkan seperti tombol Pulihkan ditekan — grup dikembalikan ke paketnya
     *    lalu sesi diputus, sebab keanggotaan address-list yang mengurungnya menempel pada SESI.
     *    Membiarkannya berarti pelanggan yang sudah membayar tetap melihat halaman tagihan sampai
     *    ia sendiri menyalakan ulang router — keluhan yang paling mahal di CS.
     */
    fun onActivated(subscriptionId: UUID) =
        subscriberAccessRepository.findBySubscriptionId(subscriptionId)
            .filter { it.status != AccessStatus.TERMINATED }
            .forEach { access ->
                val previous = access.status
                access.activate()
                subscriberAccessRepository.save(access)
                when (previous) {
                    AccessStatus.PENDING -> enqueueProvisioning(access)
                    AccessStatus.ISOLATED -> bngActions.enqueueRestore(
                        access, catalogApi.findPlanNetwork(access.planId),
                        requestedBy = null, requestedByEmail = null,
                    )
                    else -> Unit
                }
            }

    /**
     * Langganan terisolir (mis. nunggak lewat tenggat) → akun dipindah ke grup isolir lalu
     * sesinya diputus. Sama persis dengan tombol Isolir, hanya pelakunya sistem: login TETAP
     * diterima, yang berubah cuma ke mana routernya melempar pelanggan.
     *
     * Akun yang masih PENDING dilewati jalur jaringannya: belum pernah ada di RADIUS, dan
     * pelanggan yang instalasinya belum rampung tak perlu diberi login hanya untuk diisolir.
     */
    fun onIsolated(subscriptionId: UUID) = forEachLive(subscriptionId) {
        val wasPending = it.status == AccessStatus.PENDING
        it.isolate()
        if (!wasPending) bngActions.enqueueIsolir(it, requestedBy = null, requestedByEmail = null)
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
     * Paket langganan berpindah → paket akun jaringan ikut pindah, lalu kecepatannya ditegakkan.
     *
     * Ini menutup celah yang paling mahal: tanpa jalur ini pelanggan yang upgrade ditagih paket
     * baru tapi BRAS terus memberi kecepatan lama (dan sebaliknya untuk downgrade, ISP memberi
     * lebih dari yang dibayar) — selisih yang tak pernah muncul di layar mana pun karena
     * `subscriber_access.plan_id` disalin sekali saat akun dibuat lalu tak pernah ditengok lagi.
     *
     * Perlakuan per status sengaja berbeda, dan bedanya penting:
     *  - ACTIVE   : keanggotaan grup ditulis ulang + CoA, supaya sesi yang sedang hidup langsung
     *               memakai kecepatan baru tanpa perlu diputus.
     *  - ISOLATED : **hanya** paketnya yang dicatat. Menulis keanggotaan grup paket di sini akan
     *               MEMBEBASKAN penunggak dari walled garden hanya karena paketnya disunting.
     *               Grup yang benar dipasang nanti oleh jalur Pulihkan.
     *  - PENDING  : belum pernah ada di RADIUS (instalasi belum rampung); cukup dicatat, aktivasi
     *               WO PSB yang akan menuliskannya.
     *
     * `enqueueSyncGroup` dipanggil untuk semua status ber-BRAS karena ia hanya memastikan definisi
     * grup paket ADA di RADIUS — tak menyentuh keanggotaan akun mana pun.
     */
    fun onPlanChanged(subscriptionId: UUID, planId: UUID) {
        val plan = catalogApi.findPlanNetwork(planId) ?: return
        subscriberAccessRepository.findBySubscriptionId(subscriptionId)
            .filter { it.status != AccessStatus.TERMINATED && it.planId != planId }
            .forEach { access ->
                access.assignPlan(planId)
                val saved = subscriberAccessRepository.save(access)
                val nasId = saved.nasId ?: return@forEach
                bngActions.enqueueSyncGroup(nasId, saved.tenantId, plan, requestedBy = null, requestedByEmail = null)
                if (saved.status == AccessStatus.ACTIVE) {
                    bngActions.enqueueProvision(saved, requestedBy = null, requestedByEmail = null)
                    bngActions.enqueueCoa(saved, plan.downMbps, plan.upMbps, requestedBy = null, requestedByEmail = null)
                }
            }
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

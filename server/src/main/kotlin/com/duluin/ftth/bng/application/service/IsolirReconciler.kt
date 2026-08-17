package com.duluin.ftth.bng.application.service

import com.duluin.ftth.bng.application.port.outbound.BngActionRepository
import com.duluin.ftth.bng.application.port.outbound.RadiusProvisioningPort
import com.duluin.ftth.bng.application.port.outbound.SubscriberAccessRepository
import com.duluin.ftth.bng.domain.model.AccessStatus
import com.duluin.ftth.bng.domain.model.RadiusGroups
import com.duluin.ftth.bng.domain.model.SubscriberAccess
import com.duluin.ftth.catalog.CatalogApi
import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.tenancy.TenantApi
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Menyelaraskan berkala apa yang DIKATAKAN aplikasi tentang isolir dengan apa yang
 * BENAR-BENAR berlaku di RADIUS.
 *
 * Perpindahan grup isolir digerakkan peristiwa (tombol Isolir/Pulihkan, tenggat tagihan),
 * dan peristiwa hanya lewat sekali. Yang terlewat pada saat itu tak pernah datang lagi:
 *
 *  - akun yang diisolir SEBELUM grup isolir ada di produk ini — hanya sesinya yang diputus,
 *    lalu CPE dial ulang dan pelanggan kembali online penuh dengan status layar "Terisolir";
 *  - aksi provisioning yang akhirnya FAILED (radius-db mati lebih lama dari batas coba-ulang);
 *  - radius-db yang dipulihkan dari cadangan ke keadaan sebelum perpindahan grup;
 *  - baris `radusergroup` yang disunting tangan saat menelusuri gangguan lalu terlupa.
 *
 * Dua arah, dan arah keduanya yang lebih mahal: pelanggan yang SUDAH MEMBAYAR tapi masih
 * tersangkut grup isolir menganggap dirinya ditipu, sedangkan penunggak yang lolos hanya
 * merugikan pendapatan. Karena itu ACTIVE-tapi-terkurung ikut diperbaiki, bukan cuma
 * ISOLATED-tapi-bebas.
 *
 * Akun yang belum punya baris di RADIUS DILEWATI, bukan dibuatkan. Ketiadaannya berarti
 * akun itu memang belum pernah diprovisikan (mis. diisolir saat instalasinya belum rampung);
 * membuatkan baris di sini sama dengan menyerahkan login yang sengaja belum diberikan.
 *
 * Pola per-tenant sama dengan [FupScheduler] & [RadiusProvisioningDispatcher]: tenant
 * dipasang satu per satu lewat [TenantContext.runAs] dan kegagalan satu tenant tak
 * menghentikan yang lain.
 */
@Component
class IsolirReconciler(
    private val tenantApi: TenantApi,
    private val radius: RadiusProvisioningPort,
    private val runner: IsolirReconcileRunner,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${ftth.radius.isolir-reconcile-interval:PT15M}")
    fun reconcile() {
        if (!radius.isConfigured()) return
        tenantApi.findActiveTenantIds().forEach { tenantId ->
            runCatching { TenantContext.runAs(tenantId) { runner.run(tenantId) } }
                .onFailure { log.warn("Rekonsiliasi isolir tenant {} gagal: {}", tenantId, it.message) }
        }
    }
}

/**
 * Pekerja rekonsiliasi satu tenant dalam transaksinya sendiri.
 *
 * Komponen terpisah dari [IsolirReconciler], bukan method privat: `@Transactional` Spring
 * berlaku lewat proxy, jadi pemanggilan dari dalam kelas yang sama tak akan dibungkus
 * transaksi. REQUIRES_NEW mengurung kegagalan ke satu tenant.
 *
 * Perbaikannya DIANTREKAN lewat [BngActionService], bukan ditulis langsung ke radius-db:
 * jalurnya jadi persis sama dengan tombol Isolir/Pulihkan — ikut teraudit di `bng_action`,
 * ikut membawa DISCONNECT yang wajib menyertainya, dan ikut penjaga urutan worker.
 */
@Component
class IsolirReconcileRunner(
    private val tenantApi: TenantApi,
    private val subscriberAccessRepository: SubscriberAccessRepository,
    private val bngActionRepository: BngActionRepository,
    private val radius: RadiusProvisioningPort,
    private val catalogApi: CatalogApi,
    private val bngActions: BngActionService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun run(tenantId: UUID) {
        val slug = tenantApi.findById(tenantId)?.slug ?: run {
            log.warn("Tenant {} tak punya slug — rekonsiliasi isolir dilewati", tenantId)
            return
        }
        execute(tenantId, slug)
    }

    /**
     * Bandingkan grup RADIUS tiap akun ber-BRAS dengan status yang dicatat aplikasi, lalu
     * antrekan perbaikan seperlunya. Dipisah dari [run] agar bisa diuji tanpa Spring.
     *
     * Hanya akun ACTIVE & ISOLATED yang dilihat: PENDING belum pernah ditulis ke RADIUS dan
     * TERMINATED sudah dicabut (kalau barisnya masih ada, itu urusan DEPROVISION yang gagal,
     * bukan isolir). Seluruh grup ditarik sekali lewat satu query — bukan per akun — sebab
     * ini berjalan atas seluruh basis pelanggan tenant, tiap putaran.
     */
    fun execute(tenantId: UUID, slug: String) {
        val candidates = subscriberAccessRepository.findActiveOnNas() +
            subscriberAccessRepository.findIsolatedOnNas()
        if (candidates.isEmpty()) return

        // Satu identitas bisa muncul dua kali hanya bila datanya rusak; associateBy memilih
        // yang terakhir, dan yang penting perbaikannya tetap satu per identitas.
        val byIdentity = candidates.associateBy { identity(slug, it) }
        val groups = radius.groupsOf(tenantId, byIdentity.keys)
        // Akun yang perbaikannya masih dalam perjalanan tak diantre ulang: putaran ini
        // membaca radius-db SEBELUM PROVISION yang tertunda sempat dieksekusi, jadi tanpa
        // penjaga ini tiap putaran menumpuk satu salinan aksi yang sama.
        val inFlight = bngActionRepository.findAccessIdsWithPendingProvisioning(candidates.map { it.id })

        var repaired = 0
        for ((identity, access) in byIdentity) {
            // Tak ada baris RADIUS = belum pernah diprovisikan. Bukan penyimpangan.
            val group = groups[identity] ?: continue
            if (access.id in inFlight) continue
            val fixed = when (access.status) {
                AccessStatus.ISOLATED -> group != RadiusGroups.ISOLIR && isolate(access)
                // Grup FUP juga bukan grup normal, dan itu SAH — throttle kuota tak ada
                // hubungannya dengan tagihan. Hanya grup isolir yang menandakan pelanggan
                // aktif sedang terkurung.
                AccessStatus.ACTIVE -> group == RadiusGroups.ISOLIR && restore(access)
                else -> false
            }
            if (fixed) repaired++
        }
        if (repaired > 0) log.info("Rekonsiliasi isolir tenant {}: {} akun diselaraskan", tenantId, repaired)
    }

    private fun isolate(access: SubscriberAccess): Boolean {
        log.warn("Akun {} berstatus ISOLATED tapi grup RADIUS-nya bukan isolir — diisolir ulang", access.username)
        return bngActions.enqueueIsolir(access, requestedBy = null, requestedByEmail = null)
    }

    private fun restore(access: SubscriberAccess): Boolean {
        log.warn("Akun {} berstatus ACTIVE tapi masih di grup isolir — dipulihkan", access.username)
        return bngActions.enqueueRestore(
            access, catalogApi.findPlanNetwork(access.planId),
            requestedBy = null, requestedByEmail = null,
        )
    }

    /**
     * Identitas radius-db per skema autentikasi — cermin [RadiusProvisioningRunner]: PPPoE/
     * Hotspot di-prefix kode tenant (`"{slug}:{username}"`), DHCP/Static memakai MAC apa
     * adanya. Salah memetakan di sini berarti seluruh akun tampak "belum diprovisikan" dan
     * rekonsiliasi diam-diam tak pernah memperbaiki apa pun.
     */
    private fun identity(slug: String, access: SubscriberAccess): String =
        if (access.authType.macBased) access.username else "$slug:${access.username}"
}

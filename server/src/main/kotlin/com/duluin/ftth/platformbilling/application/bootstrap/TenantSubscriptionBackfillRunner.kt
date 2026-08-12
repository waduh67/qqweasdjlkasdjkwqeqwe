package com.duluin.ftth.platformbilling.application.bootstrap

import com.duluin.ftth.platformbilling.application.port.inbound.ProvisionTenantSubscriptionUseCase
import com.duluin.ftth.platformbilling.application.port.outbound.TenantSubscriptionRepository
import com.duluin.ftth.tenancy.TenantApi
import com.duluin.ftth.tenancy.TenantStatus
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component

/**
 * Pastikan SETIAP tenant lama punya langganan SaaS (harga default global) supaya halaman
 * "Langganan" sisi tenant langsung berfungsi tanpa super-admin mengonfigurasi manual dulu.
 * Idempotent: tenant yang sudah punya langganan dilewati (tak menimpa harga berjalan).
 * Berjalan setelah katalog izin & tenant tersinkron.
 *
 * Sekalian memulihkan tenant yang terlanjur di-suspend KERAS oleh aturan penagihan lama —
 * lihat [unlockTenantsSuspendedByOldRule].
 */
@Component
@Order(2)
class TenantSubscriptionBackfillRunner(
    private val tenantApi: TenantApi,
    private val subscriptionProvisioner: ProvisionTenantSubscriptionUseCase,
    private val subscriptions: TenantSubscriptionRepository,
) : ApplicationRunner {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun run(args: ApplicationArguments) {
        // Tenant "platform" (super-admin) bukan pelanggan aplikasi → jangan dibuatkan langganan/ditagih.
        val platformId = tenantApi.platformTenantId()
        val tenantIds = tenantApi.findActiveTenantIds().filterNot { it == platformId }
        tenantIds.forEach { tenantId ->
            subscriptionProvisioner.ensureForTenant(tenantId, monthlyFeeOverride = null)
        }
        log.info("Langganan dipastikan ada untuk {} tenant.", tenantIds.size)
        unlockTenantsSuspendedByOldRule()
    }

    /**
     * Sebelum kunci baca-saja ada, scheduler men-suspend TENANT-nya saat langganan lewat masa
     * tenggang — dan tenant yang tersuspend tak bisa login sama sekali. Deploy yang membawa
     * aturan baru akan meninggalkan mereka terkunci selamanya: aturan yang menutup pintu sudah
     * dihapus, tapi pintu yang terlanjur tertutup tak ada yang membukanya.
     *
     * Yang dipulihkan hanya tenant yang langganannya SUSPENDED, karena itulah tanda alasannya
     * memang menunggak. Suspend manual platform admin (langganan tetap ACTIVE/PAST_DUE) tak
     * disentuh — itu keputusan orang, bukan efek samping. Kuncinya sendiri tak hilang: ia kini
     * datang dari status langganan lewat `ReadOnlyLockGuard`. Idempoten.
     */
    private fun unlockTenantsSuspendedByOldRule() {
        val restored = subscriptions.findSuspended().count { subscription ->
            val tenant = tenantApi.findById(subscription.tenantId)
            if (tenant?.status != TenantStatus.SUSPENDED) return@count false
            tenantApi.activate(subscription.tenantId)
            log.info(
                "Tenant {} dibuka dari suspend lama; konsolnya kini baca-saja sampai tagihan lunas",
                subscription.tenantId,
            )
            true
        }
        if (restored > 0) log.info("{} tenant dipulihkan dari suspend keras aturan penagihan lama.", restored)
    }
}

package com.duluin.ftth.platformbilling.application.bootstrap

import com.duluin.ftth.platformbilling.application.port.inbound.ProvisionTenantSubscriptionUseCase
import com.duluin.ftth.tenancy.TenantApi
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
 */
@Component
@Order(2)
class TenantSubscriptionBackfillRunner(
    private val tenantApi: TenantApi,
    private val subscriptionProvisioner: ProvisionTenantSubscriptionUseCase,
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
    }
}

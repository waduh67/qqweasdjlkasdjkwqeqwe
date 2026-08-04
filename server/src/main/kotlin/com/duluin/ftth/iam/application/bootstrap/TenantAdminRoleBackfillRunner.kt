package com.duluin.ftth.iam.application.bootstrap

import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.iam.application.service.AdminProvisioner
import com.duluin.ftth.tenancy.TenantApi
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component

/**
 * Selaraskan izin role bawaan "Tenant Admin" di SETIAP tenant lama dengan katalog
 * izin terkini. Tenant baru mendapat set izin lengkap saat onboarding; runner ini
 * menutup tenant yang sudah ada sebelum sebuah izin baru diperkenalkan — tanpa ini,
 * role "Tenant Admin" mereka beku di set lama dan menu yang di-gate izin baru tak
 * pernah muncul. Idempotent (ensureTenantAdminRole hanya replacePermissions saat
 * set berbeda). Berjalan setelah katalog izin tersinkron ([IamBootstrapRunner],
 * `@Order(0)`) agar id izin sudah tersedia untuk dirangkai.
 */
@Component
@Order(1)
class TenantAdminRoleBackfillRunner(
    private val tenantApi: TenantApi,
    private val provisioner: AdminProvisioner,
) : ApplicationRunner {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun run(args: ApplicationArguments) {
        val tenantIds = tenantApi.findActiveTenantIds()
        tenantIds.forEach { tenantId ->
            TenantContext.runAs(tenantId) { provisioner.ensureTenantAdminRole(tenantId) }
        }
        log.info("Izin role 'Tenant Admin' diselaraskan di {} tenant.", tenantIds.size)
    }
}

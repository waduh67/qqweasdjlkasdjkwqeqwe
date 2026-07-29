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
 * Pastikan role sistem "Teknisi" ada di SETIAP tenant yang sudah terlanjur dibuat
 * sebelum role ini diperkenalkan. Tenant baru mendapatkannya lewat onboarding;
 * runner ini menutup tenant lama. Idempotent (ensureTechnicianRole cek by-name),
 * jadi aman dijalankan tiap startup. Berjalan setelah katalog izin tersinkron
 * ([IamBootstrapRunner], `@Order(0)`) agar id izin sudah tersedia untuk dirangkai.
 */
@Component
@Order(1)
class TechnicianRoleBackfillRunner(
    private val tenantApi: TenantApi,
    private val provisioner: AdminProvisioner,
) : ApplicationRunner {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun run(args: ApplicationArguments) {
        val tenantIds = tenantApi.findActiveTenantIds()
        tenantIds.forEach { tenantId ->
            TenantContext.runAs(tenantId) { provisioner.ensureTechnicianRole(tenantId) }
        }
        log.info("Role 'Teknisi' dipastikan ada di {} tenant.", tenantIds.size)
    }
}

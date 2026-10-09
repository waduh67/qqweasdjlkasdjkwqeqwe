package com.duluin.ftth.iam.application.bootstrap

import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.iam.application.service.AdminProvisioner
import com.duluin.ftth.tenancy.TenantApi
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component

@Component
@Order(1)
class TenantDefaultRoleBackfillRunner(
    private val tenantApi: TenantApi,
    private val provisioner: AdminProvisioner,
) : ApplicationRunner {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun run(args: ApplicationArguments) {
        val tenantIds = tenantApi.findAllTenantIds().filter { it != tenantApi.platformTenantId() }
        tenantIds.forEach { tenantId ->
            TenantContext.runAs(tenantId) { provisioner.ensureOperationalRoles(tenantId) }
        }
        log.info("Role operasional bawaan dipastikan ada di {} tenant.", tenantIds.size)
    }
}

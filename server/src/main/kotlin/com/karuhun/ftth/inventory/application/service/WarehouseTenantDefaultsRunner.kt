package com.karuhun.ftth.inventory.application.service

import com.karuhun.ftth.common.tenant.TenantContext
import com.karuhun.ftth.iam.TenantOnboardedEvent
import com.karuhun.ftth.tenancy.TenantApi
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.context.event.EventListener
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component

@Component
@Order(2)
class WarehouseTenantDefaultsRunner(
    private val tenants: TenantApi,
    private val defaults: WarehouseTenantDefaults,
) : ApplicationRunner {
    override fun run(args: ApplicationArguments) {
        tenants.findAllTenantIds().filter { it != tenants.platformTenantId() }.forEach { tenant ->
            TenantContext.runAs(tenant) { defaults.ensureWarehouse() }
        }
    }

    @EventListener
    fun on(event: TenantOnboardedEvent) {
        TenantContext.runAs(event.tenantId) { defaults.ensureWarehouse() }
    }
}

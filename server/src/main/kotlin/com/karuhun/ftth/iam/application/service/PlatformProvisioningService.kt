package com.karuhun.ftth.iam.application.service

import com.karuhun.ftth.common.tenant.TenantContext
import com.karuhun.ftth.iam.application.port.inbound.PlatformAdminCommand
import com.karuhun.ftth.iam.application.port.inbound.PlatformProvisioningUseCase
import com.karuhun.ftth.tenancy.TenantApi
import org.springframework.stereotype.Service

@Service
class PlatformProvisioningService(
    private val tenantApi: TenantApi,
    private val provisioner: AdminProvisioner,
) : PlatformProvisioningUseCase {

    override fun ensurePlatformAdmin(command: PlatformAdminCommand): Boolean {
        val platform = tenantApi.ensureTenant(PLATFORM_SLUG, "Platform")
        return TenantContext.runAs(platform.id) {
            provisioner.provisionPlatformAdmin(platform.id, command.email, command.name, command.password)
        }
    }

    companion object {
        const val PLATFORM_SLUG = "platform"
    }
}

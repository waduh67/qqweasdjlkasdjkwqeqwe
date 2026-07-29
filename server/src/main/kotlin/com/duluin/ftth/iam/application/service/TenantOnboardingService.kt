package com.duluin.ftth.iam.application.service

import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.iam.application.port.inbound.OnboardTenantCommand
import com.duluin.ftth.iam.application.port.inbound.OnboardTenantResult
import com.duluin.ftth.iam.application.port.inbound.OnboardTenantUseCase
import com.duluin.ftth.tenancy.TenantApi
import org.springframework.stereotype.Service

/**
 * Onboarding tenant baru: buat tenant (module tenancy) lalu provisioning admin
 * awalnya di dalam tenant context tenant tersebut. Idempotent.
 */
@Service
class TenantOnboardingService(
    private val tenantApi: TenantApi,
    private val provisioner: AdminProvisioner,
) : OnboardTenantUseCase {

    override fun onboard(command: OnboardTenantCommand): OnboardTenantResult {
        val tenant = tenantApi.ensureTenant(command.slug, command.name)
        val adminCreated = TenantContext.runAs(tenant.id) {
            val created = provisioner.provisionTenantAdmin(
                tenantId = tenant.id,
                email = command.adminEmail,
                name = command.adminName,
                password = command.adminPassword,
            )
            // Role sistem "Teknisi" untuk aplikasi teknisi mobile — tersedia sejak onboarding.
            provisioner.ensureTechnicianRole(tenant.id)
            created
        }
        return OnboardTenantResult(tenant = tenant, adminUserCreated = adminCreated)
    }
}

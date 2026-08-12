package com.duluin.ftth.iam.application.service

import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.iam.TenantAdminProvisionedEvent
import com.duluin.ftth.iam.TenantOnboardedEvent
import com.duluin.ftth.iam.application.port.inbound.OnboardTenantCommand
import com.duluin.ftth.iam.application.port.inbound.OnboardTenantResult
import com.duluin.ftth.iam.application.port.inbound.OnboardTenantUseCase
import com.duluin.ftth.tenancy.TenantApi
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service

/**
 * Onboarding tenant baru: buat tenant (module tenancy) lalu provisioning admin
 * awalnya di dalam tenant context tenant tersebut. Idempotent.
 */
@Service
class TenantOnboardingService(
    private val tenantApi: TenantApi,
    private val provisioner: AdminProvisioner,
    private val events: ApplicationEventPublisher,
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
        // Provisioning langganan SaaS lewat event (bukan panggilan langsung) agar iam tak
        // bergantung statis pada platformbilling — lihat [TenantOnboardedEvent]. Idempotent.
        events.publishEvent(TenantOnboardedEvent(tenant.id, command.monthlyFee))
        // Email selamat datang HANYA untuk admin yang sungguh baru dibuat. Onboarding boleh
        // dijalankan ulang atas slug yang sama; orang yang sudah lama memakai konsolnya tak
        // boleh menerima "selamat datang, ini kode ISP Anda" untuk kedua kalinya. Karena terbit
        // di sini, /signup dan /platform/tenants sama-sama tercakup tanpa cabang.
        if (adminCreated) {
            events.publishEvent(
                TenantAdminProvisionedEvent(
                    tenantId = tenant.id,
                    tenantSlug = tenant.slug,
                    tenantName = tenant.name,
                    adminEmail = command.adminEmail.trim(),
                    adminName = command.adminName.trim(),
                ),
            )
        }
        return OnboardTenantResult(tenant = tenant, adminUserCreated = adminCreated)
    }
}

package com.duluin.ftth.iam.application.bootstrap

import com.duluin.ftth.common.infrastructure.config.BootstrapProperties
import com.duluin.ftth.iam.application.port.inbound.OnboardTenantCommand
import com.duluin.ftth.iam.application.port.inbound.OnboardTenantUseCase
import com.duluin.ftth.iam.application.port.inbound.PlatformAdminCommand
import com.duluin.ftth.iam.application.port.inbound.PlatformProvisioningUseCase
import com.duluin.ftth.iam.application.service.PermissionCatalogSeeder
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component

/**
 * Inisialisasi saat startup, berurutan:
 *  1. sinkronkan katalog izin ke DB,
 *  2. pastikan tenant platform + platform admin ada,
 *  3. (opsional) onboarding tenant demo beserta adminnya.
 * Semua langkah idempotent sehingga aman dijalankan setiap kali aplikasi start.
 */
@Component
@Order(0)
class IamBootstrapRunner(
    private val permissionCatalogSeeder: PermissionCatalogSeeder,
    private val platformProvisioning: PlatformProvisioningUseCase,
    private val onboarding: OnboardTenantUseCase,
    private val properties: BootstrapProperties,
) : ApplicationRunner {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun run(args: ApplicationArguments) {
        permissionCatalogSeeder.sync()
        log.info("Katalog izin tersinkron.")

        val platformCreated = platformProvisioning.ensurePlatformAdmin(
            PlatformAdminCommand(
                email = properties.platformAdminEmail,
                name = "Platform Administrator",
                password = properties.platformAdminPassword,
            ),
        )
        log.info("Platform admin {} ({})", if (platformCreated) "dibuat" else "sudah ada", properties.platformAdminEmail)

        if (properties.seedDemoTenant) {
            val result = onboarding.onboard(
                OnboardTenantCommand(
                    slug = properties.demoTenantSlug,
                    name = properties.demoTenantName,
                    adminEmail = properties.demoAdminEmail,
                    adminName = properties.demoAdminName,
                    adminPassword = properties.demoAdminPassword,
                ),
            )
            log.info(
                "Tenant demo '{}' siap; admin {} ({})",
                result.tenant.slug,
                if (result.adminUserCreated) "dibuat" else "sudah ada",
                properties.demoAdminEmail,
            )
        }
    }
}

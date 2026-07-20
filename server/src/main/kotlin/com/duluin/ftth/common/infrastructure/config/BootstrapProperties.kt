package com.duluin.ftth.common.infrastructure.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "ftth.bootstrap")
data class BootstrapProperties(
    val platformAdminEmail: String,
    val platformAdminPassword: String,
    val seedDemoTenant: Boolean = true,
    val demoTenantSlug: String = "demo",
    val demoTenantName: String = "Demo ISP",
    val demoAdminEmail: String = "admin@demo.ftth",
    val demoAdminName: String = "Admin Demo",
    val demoAdminPassword: String = "admin12345",
)

package com.duluin.ftth.common.infrastructure.config

import org.springframework.beans.factory.SmartInitializingSingleton
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties
import com.duluin.ftth.common.infrastructure.storage.StorageProperties
import org.springframework.stereotype.Component

@Component
class ProductionConfigurationValidator(
    @Value("\${ftth.production:false}") private val production: Boolean,
    private val security: SecurityProperties,
    private val bootstrap: BootstrapProperties,
    private val dataSource: DataSourceProperties,
    private val storage: StorageProperties,
) : SmartInitializingSingleton {
    override fun afterSingletonsInstantiated() {
        if (production) requireSafeProductionConfiguration(ProductionConfiguration(security, bootstrap, dataSource, storage))
    }

    companion object {
        fun requireSafeProductionConfiguration(configuration: ProductionConfiguration) {
            val configuredValues = listOf(
                configuration.security.jwtSecret,
                configuration.security.encryptionSecret,
                configuration.dataSource.password,
                configuration.bootstrap.platformAdminPassword,
                configuration.bootstrap.demoAdminPassword,
                configuration.storage.accessKey,
                configuration.storage.secretKey,
            )
            require(configuredValues.all(::isProductionSecret)) {
                "Production configuration contains a blank, placeholder, weak, or known development default secret"
            }
            require(configuration.security.jwtSecret != configuration.security.encryptionSecret) {
                "Production JWT and encryption secrets must differ"
            }
            require(!configuration.bootstrap.seedDemoTenant) {
                "Production configuration must not seed the demo tenant"
            }
        }

        private fun isProductionSecret(value: String?): Boolean {
            val normalized = value?.trim()?.lowercase() ?: return false
            return normalized.length >= 16 && normalized !in DEVELOPMENT_DEFAULTS &&
                !normalized.startsWith("ganti-") && !normalized.contains("change-me") &&
                !normalized.contains("example") && !normalized.contains("contoh") && !normalized.contains("demo")
        }

        private val DEVELOPMENT_DEFAULTS = setOf(
            "dev-only-secret-ftth-oss-change-me-in-production!",
            "dev-only-encryption-key-ftth-change-me-too!",
            "ftth",
            "rootadmin123",
            "admin12345",
        )
    }
}

data class ProductionConfiguration(
    val security: SecurityProperties,
    val bootstrap: BootstrapProperties,
    val dataSource: DataSourceProperties,
    val storage: StorageProperties,
)

package com.duluin.ftth.common.infrastructure.config

import org.assertj.core.api.Assertions.assertThatThrownBy
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties
import com.duluin.ftth.common.infrastructure.storage.StorageProperties
import org.junit.jupiter.api.Test

class SecurityPropertiesTest {
    @Test
    fun `known development jwt secret is rejected for production configuration`() {
        assertThatThrownBy {
            productionConfiguration(jwt = "dev-only-secret-ftth-oss-change-me-in-production!")
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("development default")
    }

    @Test
    fun `known development encryption secret is rejected for production configuration`() {
        assertThatThrownBy {
            productionConfiguration(encryption = "dev-only-encryption-key-ftth-change-me-too!")
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("development default")
    }

    @Test
    fun `known development database and bootstrap secrets are rejected for production configuration`() {
        assertThatThrownBy {
            productionConfiguration(databasePassword = "ftth", bootstrapPassword = "rootadmin123")
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("development default")
    }

    @Test
    fun `blank and env example placeholders are rejected for production configuration`() {
        assertThatThrownBy { productionConfiguration(storageSecret = "ganti-minio-password-min-8-karakter") }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { productionConfiguration(databasePassword = "") }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `distinct strong production values pass validation`() {
        productionConfiguration()
    }

    private fun productionConfiguration(
        jwt: String = "j".repeat(32), encryption: String = "e".repeat(32), databasePassword: String = "database-password-strong", bootstrapPassword: String = "bootstrap-password-strong", storageSecret: String = "storage-secret-strong",
    ) = ProductionConfigurationValidator.requireSafeProductionConfiguration(
        ProductionConfiguration(
            SecurityProperties(jwt, encryption),
            BootstrapProperties("operator@company.test", bootstrapPassword, seedDemoTenant = false, demoAdminPassword = "legacy-password-strong"),
            DataSourceProperties().apply { password = databasePassword },
            StorageProperties(accessKey = "storage-access-strong", secretKey = storageSecret),
        ),
    )
}

package com.duluin.ftth.common.infrastructure.persistence.multitenancy

import org.springframework.boot.hibernate.autoconfigure.HibernatePropertiesCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import javax.sql.DataSource

/**
 * Menyambungkan strategi multi-tenancy Hibernate (mode DATABASE per-connection)
 * ke DataSource aplikasi. Bersifat foundational & lintas-module sehingga tinggal
 * di shared kernel `common` — tidak ada module yang perlu bergantung pada module
 * `tenancy` hanya agar persistensinya ter-scope tenant.
 */
@Configuration
class HibernateMultiTenancyConfig {

    @Bean
    fun multiTenancyHibernateCustomizer(dataSource: DataSource): HibernatePropertiesCustomizer =
        HibernatePropertiesCustomizer { properties ->
            properties["hibernate.multi_tenant_connection_provider"] = TenantConnectionProvider(dataSource)
            properties["hibernate.tenant_identifier_resolver"] = TenantIdentifierResolver()
        }
}

package com.duluin.ftth.iam.adapter.outbound.persistence

import com.duluin.ftth.common.infrastructure.persistence.TenantTransactionJdbc
import com.duluin.ftth.common.tenant.TenantContext
import org.springframework.stereotype.Component
import java.util.UUID

@Component
class DefaultRoleFeatureStore(private val jdbc: TenantTransactionJdbc) {
    fun claim(roleId: UUID, feature: String): Boolean {
        var inserted = false
        jdbc.withinTenant(TenantContext.tenantId()) { connection ->
            connection.prepareStatement("INSERT INTO iam_default_role_feature(tenant_id,role_id,feature) VALUES (?,?,?) ON CONFLICT DO NOTHING").use {
                it.setObject(1, TenantContext.tenantId())
                it.setObject(2, roleId)
                it.setString(3, feature)
                inserted = it.executeUpdate() == 1
            }
        }
        return inserted
    }
}

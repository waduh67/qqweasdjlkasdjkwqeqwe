package com.duluin.ftth.customer.adapter.outbound.persistence

import com.duluin.ftth.common.domain.error.ConflictException
import com.duluin.ftth.common.tenant.TenantContext
import java.sql.Connection
import java.util.UUID

internal fun Connection.validateOnuEpisode(onuId: UUID) {
    prepareStatement("SELECT warehouse_assert_asset_episodes(tenant_id,asset_id) FROM onu WHERE tenant_id=? AND id=?").use { query ->
        query.setObject(1, TenantContext.tenantId()); query.setObject(2, onuId)
        query.executeQuery().use { row -> if (!row.next()) throw ConflictException("ASSET_EPISODE_REQUIRED") }
    }
}

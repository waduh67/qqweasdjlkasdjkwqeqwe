package com.duluin.ftth.inventory.application.service

import com.duluin.ftth.common.infrastructure.persistence.TenantTransactionJdbc
import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.iam.CurrentAuthorityApi
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class WarehouseTenantDefaults(
    private val jdbc: TenantTransactionJdbc,
    private val authority: CurrentAuthorityApi,
) {
    @Transactional
    fun ensureWarehouse() {
        val tenant = TenantContext.tenantId()
        val fence = authority.lockForChange()
        jdbc.withinTenant(tenant) { connection ->
            connection.prepareStatement("SELECT warehouse_lock_location_topology(?)").use {
                it.setObject(1, tenant)
                it.execute()
            }
            val exists = connection.prepareStatement("SELECT EXISTS(SELECT FROM inventory_location WHERE tenant_id=? AND kind='WAREHOUSE')").use {
                it.setObject(1, tenant)
                it.executeQuery().use { rows -> rows.next(); rows.getBoolean(1) }
            }
            if (exists) return@withinTenant
            val codes = connection.prepareStatement("SELECT code FROM inventory_location WHERE tenant_id=?").use {
                it.setObject(1, tenant)
                it.executeQuery().use { rows -> buildSet { while (rows.next()) add(rows.getString(1)) } }
            }
            var code = "GUDANG-UTAMA"
            var suffix = 1
            while (code in codes) code = "GUDANG-UTAMA-${suffix++}"
            connection.prepareStatement("""INSERT INTO inventory_location(id,tenant_id,code,name,kind,issue_eligible,tenant_default)
                VALUES (?,?,?,'Gudang Utama','WAREHOUSE',true,true)""").use {
                it.setObject(1, UUID.randomUUID())
                it.setObject(2, tenant)
                it.setString(3, code)
                it.executeUpdate()
            }
            fence.incrementEpoch()
        }
    }
}

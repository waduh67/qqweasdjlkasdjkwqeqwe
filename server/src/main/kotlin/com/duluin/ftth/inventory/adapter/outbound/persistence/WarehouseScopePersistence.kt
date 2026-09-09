package com.duluin.ftth.inventory.adapter.outbound.persistence

import com.duluin.ftth.common.security.AuthorityFence
import com.duluin.ftth.common.security.AuthorityScope
import com.duluin.ftth.iam.CurrentAuthorityApi
import com.duluin.ftth.iam.IamApi
import com.duluin.ftth.inventory.*
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class WarehouseScopePersistence(
    private val jdbc: WarehouseCommandJdbc,
    private val authority: CurrentAuthorityApi,
    private val iam: IamApi,
    private val cutover: InventoryTenantCutoverApi,
) : InventoryWarehouseScopeApi {
    override fun currentUnderFence(authority: AuthorityFence): AuthorityScope {
        authority.assertHeld()
        return jdbc.execute { sql ->
            check(sql.tenant == authority.identity.tenantId)
            AuthorityScope.Restricted(sql.query("""WITH RECURSIVE scoped(id) AS (
                SELECT location_id FROM inventory_warehouse_scope WHERE tenant_id=? AND user_id=? AND state='ACTIVE'
                UNION SELECT location.id FROM inventory_location location JOIN scoped ON location.parent_location_id=scoped.id
                WHERE location.tenant_id=? AND location.state='ACTIVE') SELECT id FROM scoped""",
                sql.tenant, authority.identity.userId, sql.tenant) { it.uuid("id") }.toSet())
        }
    }

    @Transactional(rollbackFor = [Exception::class])
    fun replace(userId: UUID, locations: Set<UUID>, expectedCutoverEpoch: Long) {
        cutover.lockForCommand(expectedCutoverEpoch, WarehouseOperationClass.CONTROL_PLANE).assertHeld()
        val change = authority.lockForChange()
        val current = authority.lockCurrent()
        if (!current.platformAdmin && "inventory.location.manage" !in current.permissions) denied()
        if (iam.findUser(userId)?.active != true) denied()
        val allowed = currentUnderFence(current.fence)
        jdbc.execute { sql -> locations.forEach { location ->
            if (!current.platformAdmin && allowed is AuthorityScope.Restricted && location !in allowed.ids) denied()
            if (sql.value("SELECT id FROM inventory_location WHERE tenant_id=? AND id=? AND state='ACTIVE'", sql.tenant, location) == null) denied()
        } }
        val epoch = change.incrementEpoch()
        jdbc.execute { sql ->
            sql.update("UPDATE inventory_warehouse_scope SET state='REVOKED',authority_epoch=?,revision=revision+1,updated_at=clock_timestamp() WHERE tenant_id=? AND user_id=? AND state='ACTIVE'",
                epoch, sql.tenant, userId)
            locations.sortedBy(UUID::toString).forEach { location ->
                sql.update("""INSERT INTO inventory_warehouse_scope(id,tenant_id,user_id,location_id,granted_by,authority_epoch)
                    VALUES (?,?,?,?,?,?) ON CONFLICT (tenant_id,user_id,location_id) DO UPDATE SET state='ACTIVE',
                    authority_epoch=excluded.authority_epoch,granted_by=excluded.granted_by,revision=inventory_warehouse_scope.revision+1,updated_at=clock_timestamp()""",
                    UUID.randomUUID(), sql.tenant, userId, location, current.fence.identity.userId, epoch)
            }
        }
    }
    private fun denied(): Nothing = throw WarehouseContractException(WarehouseError(WarehouseErrorCode.FORBIDDEN, "Warehouse scope access denied"))
}

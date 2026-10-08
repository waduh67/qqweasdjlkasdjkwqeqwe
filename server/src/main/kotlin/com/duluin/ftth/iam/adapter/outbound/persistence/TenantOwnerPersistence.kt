package com.duluin.ftth.iam.adapter.outbound.persistence

import com.duluin.ftth.common.infrastructure.persistence.TenantTransactionJdbc
import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.iam.application.port.outbound.TenantOwnerStore
import com.duluin.ftth.iam.TenantOwnerRef
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class TenantOwnerPersistence(private val jdbc: TenantTransactionJdbc) : TenantOwnerStore {
    override fun findProfiles(tenantIds: Set<UUID>): Map<UUID, TenantOwnerRef?> = tenantIds.associateWith { tenantId ->
        var owner: TenantOwnerRef? = null
        jdbc.withinTenant(tenantId) { connection ->
            connection.prepareStatement("""SELECT u.id,u.name,u.email,u.status FROM iam_tenant_owner o
                JOIN app_user u ON u.tenant_id=o.tenant_id AND u.id=o.user_id
                WHERE o.tenant_id=?""").use { query ->
                query.setObject(1, tenantId)
                query.executeQuery().use { rows ->
                    if (rows.next()) owner = TenantOwnerRef(rows.getObject(1, UUID::class.java),
                        rows.getString(2), rows.getString(3), rows.getString(4))
                }
            }
        }
        owner
    }

    override fun backfillInitialAdmin(): Boolean {
        var inserted = false
        jdbc.withinTenant(TenantContext.tenantId()) { connection ->
            connection.prepareStatement("""WITH first_users AS (
                SELECT u.id FROM app_user u WHERE u.tenant_id=? AND NOT u.platform_admin
                AND u.created_at=(SELECT min(created_at) FROM app_user WHERE tenant_id=? AND NOT platform_admin)
            ) INSERT INTO iam_tenant_owner(tenant_id,user_id)
                SELECT ?,u.id FROM first_users u WHERE (SELECT count(*) FROM first_users)=1
                AND EXISTS(SELECT FROM user_role ur JOIN role r ON r.id=ur.role_id
                    WHERE ur.user_id=u.id AND r.tenant_id=? AND r.default_key='TENANT_OWNER_LEGACY')
                ON CONFLICT (tenant_id) DO NOTHING""").use { statement ->
                (1..4).forEach { statement.setObject(it, TenantContext.tenantId()) }
                inserted = statement.executeUpdate() == 1
            }
        }
        return inserted
    }

    override fun findUserId(): UUID? {
        var result: UUID? = null
        jdbc.withinTenant(TenantContext.tenantId()) { connection ->
            connection.prepareStatement("SELECT user_id FROM iam_tenant_owner WHERE tenant_id=?").use { statement ->
                statement.setObject(1, TenantContext.tenantId())
                statement.executeQuery().use { rows -> if (rows.next()) result = rows.getObject(1, UUID::class.java) }
            }
        }
        return result
    }

    override fun bindIfMissing(userId: UUID): Boolean {
        var inserted = false
        jdbc.withinTenant(TenantContext.tenantId()) { connection ->
            connection.prepareStatement("INSERT INTO iam_tenant_owner(tenant_id,user_id) VALUES (?,?) ON CONFLICT (tenant_id) DO NOTHING").use { statement ->
                statement.setObject(1, TenantContext.tenantId())
                statement.setObject(2, userId)
                inserted = statement.executeUpdate() == 1
            }
        }
        return inserted
    }

    override fun bind(userId: UUID, actorId: UUID) {
        jdbc.withinTenant(TenantContext.tenantId()) { connection ->
            connection.prepareStatement("""INSERT INTO iam_tenant_owner(tenant_id,user_id,assigned_by) VALUES (?,?,?)
                ON CONFLICT (tenant_id) DO UPDATE SET user_id=excluded.user_id,assigned_by=excluded.assigned_by,assigned_at=clock_timestamp()""").use { statement ->
                statement.setObject(1, TenantContext.tenantId())
                statement.setObject(2, userId)
                statement.setObject(3, actorId)
                statement.executeUpdate()
            }
        }
    }
}

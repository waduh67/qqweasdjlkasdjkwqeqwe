package com.duluin.ftth.iam.adapter.outbound.persistence

import com.duluin.ftth.common.security.AuthorityFence
import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.iam.*
import jakarta.persistence.EntityManager
import org.hibernate.Session
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.util.UUID

@Repository
class ApprovalAuthorityPersistence(private val entityManager: EntityManager) : ApprovalAuthorityApi {
    override fun directory(fence: AuthorityFence): ApprovalAuthorityDirectory {
        fence.assertHeld()
        check(fence.identity.tenantId == TenantContext.tenantId())
        return entityManager.unwrap(Session::class.java).doReturningWork { connection ->
            fun <T> query(sql: String, read: (ResultSet) -> T): List<T> = connection.prepareStatement(sql).use { statement ->
                statement.setObject(1, fence.identity.tenantId)
                statement.executeQuery().use { rows -> buildList { while (rows.next()) add(read(rows)) } }
            }
            val roleGrants = query("""SELECT role.id,permission.code FROM role LEFT JOIN role_permission grant_row ON grant_row.role_id=role.id
                LEFT JOIN permission ON permission.id=grant_row.permission_id AND permission.active AND NOT permission.platform_only
                WHERE role.tenant_id=?""") { it.getObject("id", UUID::class.java) to it.getString("code") }
                .groupBy({ it.first }, { it.second }).mapValues { (_, codes) -> codes.filterNotNull().toSet() }
            val memberships = query("""SELECT link.user_id,link.role_id FROM user_role link JOIN app_user ON app_user.id=link.user_id
                JOIN role ON role.id=link.role_id AND role.tenant_id=app_user.tenant_id WHERE app_user.tenant_id=? AND app_user.status='ACTIVE'""") {
                it.getObject("user_id", UUID::class.java) to it.getObject("role_id", UUID::class.java)
            }.groupBy({ it.first }, { it.second })
            val areas = query("""SELECT link.user_id,link.area_id FROM user_area link JOIN app_user ON app_user.id=link.user_id
                JOIN area ON area.id=link.area_id AND area.tenant_id=app_user.tenant_id WHERE app_user.tenant_id=? AND app_user.status='ACTIVE'""") {
                it.getObject("user_id", UUID::class.java) to it.getObject("area_id", UUID::class.java)
            }.groupBy({ it.first }, { it.second })
            val users = query("SELECT id FROM app_user WHERE tenant_id=? AND status='ACTIVE' ORDER BY id") { it.getObject(1, UUID::class.java) }
                .map { id ->
                    val roles = memberships[id].orEmpty().toSet()
                    ApprovalPrincipal(id, roles, roles.flatMap { roleGrants[it].orEmpty() }.toSet(), areas[id].orEmpty().toSet())
                }
            ApprovalAuthorityDirectory(users, roleGrants)
        }
    }
}

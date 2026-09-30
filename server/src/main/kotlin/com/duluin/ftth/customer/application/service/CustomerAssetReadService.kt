package com.duluin.ftth.customer.application.service

import com.duluin.ftth.common.security.AuthorityFence
import com.duluin.ftth.common.security.AuthorityScope
import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.customer.*
import com.duluin.ftth.customer.application.port.outbound.CustomerRepository
import com.duluin.ftth.iam.CurrentAuthorityApi
import com.duluin.ftth.inventory.*
import jakarta.persistence.EntityManager
import org.hibernate.Session
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
@Transactional(propagation = Propagation.MANDATORY, rollbackFor = [Exception::class])
class CustomerAssetReadService(private val customers: CustomerRepository, private val authorities: CurrentAuthorityApi,
    private val entityManager: EntityManager) : CustomerAssetReadApi {
    override fun authorize(customerId: UUID, authority: AuthorityFence): CustomerAssetWorkspace {
        authority.assertHeld()
        val current = authorities.lockCurrent()
        if (authority.identity != current.fence.identity || authority.epoch != current.fence.epoch) fail(WarehouseErrorCode.STALE_AUTHORITY)
        if (!current.platformAdmin && "customer.onu.view" !in current.permissions) fail(WarehouseErrorCode.FORBIDDEN)
        val customer = customers.findById(customerId) ?: fail(WarehouseErrorCode.NOT_FOUND)
        val scope = current.areaScope
        if (scope is AuthorityScope.Restricted && customer.areaId !in scope.ids) fail(WarehouseErrorCode.NOT_FOUND)
        val count = entityManager.unwrap(Session::class.java).doReturningWork { connection ->
            connection.prepareStatement("SELECT count(*) FROM onu WHERE tenant_id=? AND customer_id=? AND warehouse_admission='LEGACY_UNRESOLVED'").use { statement ->
                statement.setObject(1, TenantContext.tenantId()); statement.setObject(2, customerId)
                statement.executeQuery().use { rows -> rows.next(); rows.getLong(1) }
            }
        }
        return CustomerAssetWorkspace(customer.id, customer.code, customer.name, customer.status.name, count)
    }
    override fun episodes(customerId: UUID, assignments: Set<UUID>, authority: AuthorityFence): Map<UUID, CustomerAssetEpisodeState> {
        authorize(customerId, authority)
        if (assignments.isEmpty()) return emptyMap()
        if (assignments.size > 100) fail(WarehouseErrorCode.MALFORMED_REQUEST)
        return entityManager.unwrap(Session::class.java).doReturningWork { connection ->
            connection.prepareStatement("""SELECT assignment_id,id,episode_revision,odp_id,odp_port_number FROM onu
                WHERE tenant_id=? AND original_customer_id=? AND assignment_id=ANY(?) AND warehouse_admission='VERIFIED'""").use { statement ->
                statement.setObject(1, TenantContext.tenantId()); statement.setObject(2, customerId)
                statement.setArray(3, connection.createArrayOf("uuid", assignments.toTypedArray()))
                statement.executeQuery().use { rows -> buildMap {
                    while (rows.next()) put(rows.getObject("assignment_id", UUID::class.java), CustomerAssetEpisodeState(
                        rows.getObject("id", UUID::class.java), rows.getLong("episode_revision"), rows.getObject("odp_id", UUID::class.java),
                        rows.getObject("odp_port_number")?.let { (it as Number).toInt() }))
                } }
            }
        }
    }
    private fun fail(code: WarehouseErrorCode): Nothing = throw WarehouseContractException(WarehouseError(code, code.name))
}

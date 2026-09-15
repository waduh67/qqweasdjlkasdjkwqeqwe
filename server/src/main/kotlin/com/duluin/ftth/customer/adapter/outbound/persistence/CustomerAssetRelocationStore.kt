package com.duluin.ftth.customer.adapter.outbound.persistence

import com.duluin.ftth.common.domain.error.ConflictException
import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.customer.*
import jakarta.persistence.EntityManager
import org.hibernate.Session
import org.springframework.stereotype.Repository
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.util.UUID

data class AssetRelocationWrite(val context: CustomerAssetRelocationContext, val request: CustomerAssetRelocationRequest,
    val actorId: UUID, val key: String, val hash: String)
data class AssetRelocationReplay(val response: CustomerAssetRelocation, val actorId: UUID, val hash: String)

@Repository
class CustomerAssetRelocationStore(private val entityManager: EntityManager) {
    private val mapper = jacksonObjectMapper()
    fun replay(key: String): AssetRelocationReplay? = entityManager.unwrap(Session::class.java).doReturningWork { connection ->
        connection.prepareStatement("SELECT pg_advisory_xact_lock(hashtextextended(?,0))").use { query ->
            query.setString(1, "asset-relocation:${TenantContext.tenantId()}:$key"); query.execute()
        }
        connection.prepareStatement("SELECT response,actor_id,payload_hash FROM customer_asset_relocation WHERE tenant_id=? AND operation_key=?").use { query ->
            query.setObject(1, TenantContext.tenantId()); query.setString(2, key)
            query.executeQuery().use { row -> if (row.next()) AssetRelocationReplay(mapper.readValue(row.getString(1), CustomerAssetRelocation::class.java),
                row.getObject(2, UUID::class.java), row.getString(3)) else null }
        }
    }
    fun append(write: AssetRelocationWrite): CustomerAssetRelocation = entityManager.unwrap(Session::class.java).doReturningWork { connection ->
        val request = write.request
        val topology = request.topology
        val onuId = connection.prepareStatement("""UPDATE onu SET odp_id=?,odp_port_number=?,install_rx_power_dbm=?
            WHERE tenant_id=? AND customer_id=? AND assignment_id=? AND retired_at IS NULL AND topology_revision=?
                AND (odp_id,odp_port_number) IS DISTINCT FROM (?,?) RETURNING id""").use { query ->
            query.setObject(1, topology.odpId); query.setInt(2, topology.portNumber); query.setObject(3, topology.installRxPowerDbm)
            query.setObject(4, TenantContext.tenantId()); query.setObject(5, write.context.customerId); query.setObject(6, write.context.assignmentId)
            query.setLong(7, request.expectedRevision); query.setObject(8, topology.odpId); query.setInt(9, topology.portNumber)
            query.executeQuery().use { row -> if (!row.next()) throw ConflictException("ASSET_TOPOLOGY_STALE"); row.getObject(1, UUID::class.java) }
        }
        val result = CustomerAssetRelocation(UUID.randomUUID(), onuId, Math.addExact(request.expectedRevision, 1), topology)
        connection.prepareStatement("""INSERT INTO customer_asset_relocation(id,tenant_id,customer_id,assignment_id,onu_id,work_order_id,actor_id,
            operation_key,payload_hash,source_revision,target_revision,target_odp_id,target_port,response) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)""").use { query ->
            query.setObject(1, result.operationId); query.setObject(2, TenantContext.tenantId()); query.setObject(3, write.context.customerId)
            query.setObject(4, write.context.assignmentId); query.setObject(5, onuId); query.setObject(6, request.workOrderId); query.setObject(7, write.actorId)
            query.setString(8, write.key); query.setString(9, write.hash); query.setLong(10, request.expectedRevision); query.setLong(11, result.revision)
            query.setObject(12, topology.odpId); query.setInt(13, topology.portNumber); query.setString(14, mapper.writeValueAsString(result)); check(query.executeUpdate() == 1)
        }
        result
    }
}

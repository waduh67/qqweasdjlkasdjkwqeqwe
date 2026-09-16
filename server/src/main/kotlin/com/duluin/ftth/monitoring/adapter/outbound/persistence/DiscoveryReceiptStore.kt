package com.duluin.ftth.monitoring.adapter.outbound.persistence

import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.inventory.WarehouseContractException
import com.duluin.ftth.inventory.WarehouseError
import com.duluin.ftth.inventory.WarehouseErrorCode
import com.duluin.ftth.monitoring.application.port.inbound.DiscoveredOnuView
import com.duluin.ftth.monitoring.application.port.inbound.ProvisionDiscoveredOnuCommand
import jakarta.persistence.EntityManager
import org.hibernate.Session
import org.springframework.stereotype.Repository
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.security.MessageDigest
import java.util.UUID

@Repository
class DiscoveryReceiptStore(private val entityManager: EntityManager) {
    private val mapper = jacksonObjectMapper()
    private fun hash(command: ProvisionDiscoveredOnuCommand) = MessageDigest.getInstance("SHA-256")
        .digest(mapper.writeValueAsBytes(command)).joinToString("") { "%02x".format(it) }

    fun find(id: UUID, command: ProvisionDiscoveredOnuCommand): DiscoveredOnuView? = entityManager.unwrap(Session::class.java).doReturningWork { connection ->
        connection.prepareStatement("SELECT request_hash,response FROM monitoring_discovery_receipt WHERE tenant_id=? AND discovery_id=?").use { query ->
            query.setObject(1, TenantContext.tenantId()); query.setObject(2, id)
            query.executeQuery().use { rows ->
                if (!rows.next()) return@doReturningWork null
                if (rows.getString(1) != hash(command)) throw WarehouseContractException(WarehouseError(WarehouseErrorCode.IDEMPOTENCY_CONFLICT, "IDEMPOTENCY_CONFLICT"))
                mapper.readValue(rows.getString(2), DiscoveredOnuView::class.java)
            }
        }
    }

    fun append(view: DiscoveredOnuView, command: ProvisionDiscoveredOnuCommand) = entityManager.unwrap(Session::class.java).doWork { connection ->
        connection.prepareStatement("""INSERT INTO monitoring_discovery_receipt(tenant_id,discovery_id,authorization_id,operation_key,request_hash,response)
            VALUES (?,?,?,?,?,?::jsonb)""").use { query ->
            query.setObject(1, TenantContext.tenantId()); query.setObject(2, view.id); query.setObject(3, command.authorizationId)
            query.setString(4, command.operationKey); query.setString(5, hash(command)); query.setString(6, mapper.writeValueAsString(view)); query.executeUpdate()
        }
    }
}

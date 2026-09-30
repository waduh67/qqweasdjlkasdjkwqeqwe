package com.duluin.ftth.customer.adapter.outbound.persistence

import com.duluin.ftth.common.domain.error.ConflictException
import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.customer.CustomerAssetEpisode
import com.duluin.ftth.inventory.AssetLossClosure
import com.duluin.ftth.inventory.AssetLossCustomerPort
import jakarta.persistence.EntityManager
import org.hibernate.Session
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.sql.Timestamp
import java.util.UUID

@Component
@Transactional(propagation = Propagation.MANDATORY)
class AssetLossCustomerAdapter(private val entityManager: EntityManager) : AssetLossCustomerPort {
    private val mapper = jacksonObjectMapper()

    override fun retire(closure: AssetLossClosure): UUID? = entityManager.unwrap(Session::class.java).doReturningWork { connection ->
        val tenant = TenantContext.tenantId()
        val original = connection.prepareStatement("SELECT response FROM customer_asset_installation WHERE tenant_id=? AND assignment_id=?").use { query ->
            query.setObject(1, tenant); query.setObject(2, closure.assignmentId)
            query.executeQuery().use { row ->
                if (!row.next()) throw ConflictException("ASSET_EPISODE_REQUIRED")
                mapper.readValue(row.getString(1), CustomerAssetEpisode::class.java)
            }
        }
        if (original.assetId != closure.assetId || original.customerId != closure.customerId)
            throw ConflictException("ASSET_EPISODE_MISMATCH")
        val retired = if (original.onuId != null) connection.prepareStatement("""UPDATE onu SET retired_at=?,status='DISMANTLED',
            odp_id=NULL,odp_port_number=NULL WHERE tenant_id=? AND id=? AND assignment_id=? AND retired_at IS NULL
            RETURNING episode_revision,retired_at""").use { query ->
            query.setTimestamp(1, Timestamp.from(closure.recordedAt)); query.setObject(2, tenant)
            query.setObject(3, original.onuId); query.setObject(4, closure.assignmentId)
            query.executeQuery().use { row ->
                if (!row.next()) throw ConflictException("ASSET_EPISODE_ALREADY_RETIRED")
                original.copy(retiredAt = row.getTimestamp("retired_at").toInstant(), episodeRevision = row.getLong("episode_revision"))
            }
        } else original.copy(retiredAt = closure.recordedAt, episodeRevision = 1)
        connection.prepareStatement("""INSERT INTO customer_asset_loss(tenant_id,request_id,operation_id,assignment_id,
            episode_id,onu_id,response,retired_at) VALUES (?,?,?,?,?,?,?,?)""").use { query ->
            query.setObject(1, tenant); query.setObject(2, closure.requestId); query.setObject(3, closure.operationId)
            query.setObject(4, closure.assignmentId); query.setObject(5, original.episodeId); query.setObject(6, original.onuId)
            query.setString(7, mapper.writeValueAsString(retired)); query.setTimestamp(8, Timestamp.from(closure.recordedAt))
            check(query.executeUpdate() == 1)
        }
        original.onuId
    }
}

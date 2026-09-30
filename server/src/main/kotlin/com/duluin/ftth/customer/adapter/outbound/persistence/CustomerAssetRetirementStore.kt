package com.duluin.ftth.customer.adapter.outbound.persistence

import com.duluin.ftth.common.domain.error.ConflictException
import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.customer.CustomerAssetEpisode
import com.duluin.ftth.inventory.AssetRemovalResult
import jakarta.persistence.EntityManager
import org.hibernate.Session
import org.springframework.stereotype.Repository
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.sql.Timestamp

@Repository
class CustomerAssetRetirementStore(private val entityManager: EntityManager) {
    private val mapper = jacksonObjectMapper()
    fun retire(result: AssetRemovalResult): CustomerAssetEpisode = entityManager.unwrap(Session::class.java).doReturningWork { connection ->
        val tenant = TenantContext.tenantId()
        connection.prepareStatement("SELECT response FROM customer_asset_retirement WHERE tenant_id=? AND removal_id=?").use { query ->
            query.setObject(1, tenant); query.setObject(2, result.operationId)
            query.executeQuery().use { row ->
                if (row.next()) {
                    val episode = mapper.readValue(row.getString(1), CustomerAssetEpisode::class.java)
                    episode.onuId?.let(connection::validateOnuEpisode)
                    return@doReturningWork episode
                }
            }
        }
        val original = connection.prepareStatement("SELECT response FROM customer_asset_installation WHERE tenant_id=? AND assignment_id=?").use { query ->
            query.setObject(1, tenant); query.setObject(2, result.assignmentId)
            query.executeQuery().use { row ->
                if (!row.next()) throw ConflictException("ASSET_EPISODE_REQUIRED")
                mapper.readValue(row.getString(1), CustomerAssetEpisode::class.java)
            }
        }
        if (original.customerId != result.customerId || original.assetId != result.assetId ||
            result.replacement?.let { it.createsOnu != (original.onuId != null) } == true) throw ConflictException("ASSET_EPISODE_MISMATCH")
        val episode = if (original.onuId != null) connection.prepareStatement("""UPDATE onu SET retired_at=?,
            status='DISMANTLED',odp_id=NULL,odp_port_number=NULL WHERE tenant_id=? AND id=? AND assignment_id=? AND retired_at IS NULL
            RETURNING episode_revision,retired_at""").use { query ->
            query.setTimestamp(1, Timestamp.from(result.removedAt)); query.setObject(2, tenant)
            query.setObject(3, original.onuId); query.setObject(4, result.assignmentId)
            query.executeQuery().use { row ->
                if (!row.next()) throw ConflictException("ASSET_EPISODE_ALREADY_RETIRED")
                original.copy(retiredAt = row.getTimestamp("retired_at").toInstant(), episodeRevision = row.getLong("episode_revision"), legalOwner = result.legalOwner)
            }
        } else original.copy(retiredAt = result.removedAt, episodeRevision = 1, legalOwner = result.legalOwner)
        connection.prepareStatement("""INSERT INTO customer_asset_retirement(tenant_id,removal_id,episode_id,onu_id,response,retired_at)
            VALUES (?,?,?,?,?,?)""").use { query ->
            query.setObject(1, tenant); query.setObject(2, result.operationId); query.setObject(3, original.episodeId)
            query.setObject(4, original.onuId); query.setString(5, mapper.writeValueAsString(episode))
            query.setTimestamp(6, Timestamp.from(result.removedAt)); check(query.executeUpdate() == 1)
        }
        episode
    }
}

package com.duluin.ftth.customer.adapter.outbound.persistence

import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.customer.CustomerAssetEpisode
import com.duluin.ftth.customer.CustomerAssetTopology
import com.duluin.ftth.inventory.DeploymentConsumption
import jakarta.persistence.EntityManager
import org.hibernate.Session
import org.springframework.stereotype.Repository
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.sql.Timestamp
import java.util.UUID

@Repository
class CustomerAssetInstallationStore(private val entityManager: EntityManager) {
    private val mapper = jacksonObjectMapper()
    fun find(operationId: UUID): CustomerAssetEpisode? = entityManager.unwrap(Session::class.java).doReturningWork { connection ->
        connection.prepareStatement("SELECT response FROM customer_asset_installation WHERE tenant_id=? AND operation_id=?").use { query ->
            query.setObject(1, TenantContext.tenantId()); query.setObject(2, operationId)
            query.executeQuery().use { row -> if (row.next()) mapper.readValue(row.getString(1), CustomerAssetEpisode::class.java) else null }
        }
    }
    fun append(consumption: DeploymentConsumption, topology: CustomerAssetTopology?): CustomerAssetEpisode {
        val assignment = consumption.assignment
        val onuId = if (consumption.createsOnu) consumption.operationId else null
        val episode = CustomerAssetEpisode(consumption.operationId, onuId, assignment.customerId, assignment.assetId,
            assignment.assignmentId, assignment.revision, 0, assignment.startedAt, null, assignment.provenance, assignment.ownershipMode, assignment.legalOwner)
        entityManager.unwrap(Session::class.java).doWork { connection ->
            check(!connection.autoCommit)
            if (onuId != null) connection.prepareStatement("""INSERT INTO onu(id,tenant_id,customer_id,original_customer_id,
                serial_number,canonical_serial,model,asset_id,assignment_id,started_at,installed_at,provenance,warehouse_admission,
                odp_id,odp_port_number,install_rx_power_dbm,status) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,'VERIFIED',?,?,?,'PENDING')""").use { query ->
                query.setObject(1, onuId); query.setObject(2, TenantContext.tenantId()); query.setObject(3, assignment.customerId)
                query.setObject(4, assignment.customerId); query.setString(5, consumption.serialNumber); query.setString(6, consumption.serialNumber)
                query.setString(7, consumption.model); query.setObject(8, assignment.assetId); query.setObject(9, assignment.assignmentId)
                query.setTimestamp(10, Timestamp.from(assignment.startedAt)); query.setTimestamp(11, Timestamp.from(assignment.startedAt))
                query.setString(12, assignment.provenance.name); query.setObject(13, topology?.odpId)
                query.setObject(14, topology?.portNumber); query.setObject(15, topology?.installRxPowerDbm)
                check(query.executeUpdate() == 1)
            }
            connection.prepareStatement("""INSERT INTO customer_asset_installation(id,tenant_id,customer_id,asset_id,assignment_id,operation_id,onu_id,response)
                VALUES (?,?,?,?,?,?,?,?)""").use { query ->
                query.setObject(1, episode.episodeId); query.setObject(2, TenantContext.tenantId()); query.setObject(3, assignment.customerId)
                query.setObject(4, assignment.assetId); query.setObject(5, assignment.assignmentId); query.setObject(6, consumption.operationId)
                query.setObject(7, onuId); query.setString(8, mapper.writeValueAsString(episode)); check(query.executeUpdate() == 1)
            }
        }
        return episode
    }
    fun history(customerId: UUID): List<CustomerAssetEpisode> = entityManager.unwrap(Session::class.java).doReturningWork { connection ->
        connection.prepareStatement("""SELECT coalesce(retirement.response,installation.response) FROM customer_asset_installation installation
            LEFT JOIN customer_asset_retirement retirement ON retirement.tenant_id=installation.tenant_id AND retirement.episode_id=installation.id
            WHERE installation.tenant_id=? AND installation.customer_id=? ORDER BY installation.created_at,installation.id""").use { query ->
            query.setObject(1, TenantContext.tenantId()); query.setObject(2, customerId)
            query.executeQuery().use { rows -> buildList { while (rows.next()) add(mapper.readValue(rows.getString(1), CustomerAssetEpisode::class.java)) } }
        }
    }
}

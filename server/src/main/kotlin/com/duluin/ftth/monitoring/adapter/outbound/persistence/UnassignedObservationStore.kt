package com.duluin.ftth.monitoring.adapter.outbound.persistence

import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.contract.OnuReading
import jakarta.persistence.EntityManager
import org.hibernate.Session
import org.springframework.stereotype.Repository
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.sql.Timestamp
import java.util.UUID

@Repository
class UnassignedObservationStore(private val entityManager: EntityManager) {
    private val mapper = jacksonObjectMapper()
    fun append(reading: OnuReading, reason: String) = appendRaw(reading.serialNumber,
        reading.observedAt, reason, mapper.writeValueAsString(reading))

    fun appendUntrustedTime(reading: OnuReading) = appendRaw(reading.serialNumber,
        null, "UNTRUSTED_TIMESTAMP", mapper.writeValueAsString(reading))

    fun appendRaw(serial: String, observedAt: java.time.Instant?, reason: String, payload: String) = entityManager.unwrap(Session::class.java).doWork { connection ->
        connection.prepareStatement("""INSERT INTO monitoring_unassigned_observation(id,tenant_id,serial_number,observed_at,reason,payload)
            VALUES (?,?,?,?,?,?::jsonb)""").use { query ->
            query.setObject(1, UUID.randomUUID()); query.setObject(2, TenantContext.tenantId())
            query.setString(3, serial); query.setTimestamp(4, observedAt?.let(Timestamp::from))
            query.setString(5, reason); query.setString(6, payload); query.executeUpdate()
        }
    }
}

package com.duluin.ftth.cpe.adapter.outbound.persistence

import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.cpe.application.port.outbound.AcsDevice
import jakarta.persistence.EntityManager
import org.hibernate.Session
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.security.MessageDigest
import java.util.Locale

@Repository
class CpeUnassignedObservationStore(private val entityManager: EntityManager) {
    @Transactional(propagation = Propagation.REQUIRED)
    fun record(snapshot: AcsDevice, reason: String) = entityManager.unwrap(Session::class.java).doWork { connection ->
        val hash = MessageDigest.getInstance("SHA-256").digest(snapshot.genieacsId.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        connection.prepareStatement("""INSERT INTO cpe_unassigned_observation(tenant_id,serial_number,source_hash,reason)
            VALUES (?,?,?,?) ON CONFLICT DO NOTHING""").use { query ->
            query.setObject(1, TenantContext.tenantId()); query.setString(2, snapshot.serialNumber.trim().uppercase(Locale.ROOT))
            query.setString(3, hash); query.setString(4, reason); query.executeUpdate()
        }
    }
}

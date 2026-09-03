package com.duluin.ftth.provisioning.adapter.outbound.persistence

import com.duluin.ftth.provisioning.application.port.outbound.ProvisioningObservationPort
import com.duluin.ftth.provisioning.application.port.outbound.ProvisioningObservationException
import com.duluin.ftth.provisioning.application.port.outbound.ProvisioningObservationFailure
import com.duluin.ftth.provisioning.domain.model.DeviceReference
import com.duluin.ftth.provisioning.domain.model.NormalizedDeviceState
import com.duluin.ftth.provisioning.domain.model.NormalizedStateHash
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration
import java.time.Instant

@Component
class ProvisioningObservationPersistenceAdapter(
    private val entityManager: EntityManager,
    private val codec: NormalizedStateJsonCodec,
    private val clock: Clock,
) : ProvisioningObservationPort {
    /** Reuses hash-verified readback persisted by the execution engine; never dispatches a device command. */
    @Transactional(readOnly = true)
    override fun observe(device: DeviceReference): NormalizedDeviceState {
        val rows = entityManager.createNativeQuery(
            """SELECT snapshot.normalized_state::text, snapshot.state_hash, snapshot.captured_at
               FROM provisioning_step_snapshot snapshot
               JOIN provisioning_execution_step step
                 ON step.id = snapshot.execution_step_id AND step.tenant_id = snapshot.tenant_id
               WHERE step.device_kind = :kind AND step.device_id = :device
                 AND snapshot.snapshot_kind IN ('AFTER', 'ROLLBACK_RESULT')
               ORDER BY snapshot.captured_at DESC, snapshot.id DESC
               LIMIT 1""",
        ).setParameter("kind", device.kind.name).setParameter("device", device.id).resultList
        val row = rows.singleOrNull() as? Array<*>
            ?: throw ProvisioningObservationException(ProvisioningObservationFailure.READBACK_UNAVAILABLE)
        val capturedAt = row[2] as Instant
        val age = Duration.between(capturedAt, clock.instant())
        if (age.isNegative || age > MAX_READBACK_AGE) {
            throw ProvisioningObservationException(ProvisioningObservationFailure.READBACK_STALE)
        }
        val state = codec.decode(row[0] as String)
        if (NormalizedStateHash.sha256(state) != row[1] as String) {
            throw ProvisioningObservationException(ProvisioningObservationFailure.READBACK_HASH_MISMATCH)
        }
        return state
    }

    private companion object { val MAX_READBACK_AGE: Duration = Duration.ofMinutes(20) }
}

package com.duluin.ftth.provisioning.application.service

import com.duluin.ftth.provisioning.adapter.outbound.persistence.NormalizedStateJsonCodec
import com.duluin.ftth.provisioning.domain.model.DeviceKind
import com.duluin.ftth.provisioning.domain.model.DeviceReference
import com.duluin.ftth.provisioning.domain.model.DeviceSnapshot
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

fun interface ProvisioningDriftBaselineReader {
    fun latestPerDevice(): List<DeviceSnapshot>
}

@Component
class JpaProvisioningDriftBaselineReader(
    private val entityManager: EntityManager,
    private val codec: NormalizedStateJsonCodec,
) : ProvisioningDriftBaselineReader {
    @Transactional(readOnly = true)
    override fun latestPerDevice(): List<DeviceSnapshot> = entityManager.createNativeQuery(
        """SELECT DISTINCT ON (device_kind, device_id)
                  id, tenant_id, device_kind, device_id, plan_id, normalized_state::text, captured_at
           FROM provisioning_device_snapshot
           ORDER BY device_kind, device_id, captured_at DESC""",
    ).resultList.map { raw ->
        val row = raw as Array<*>
        DeviceSnapshot.rehydrate(
            row[0] as UUID,
            row[1] as UUID,
            DeviceReference(DeviceKind.valueOf(row[2] as String), row[3] as UUID),
            row[4] as UUID,
            codec.decode(row[5] as String),
            row[6] as Instant,
        )
    }
}

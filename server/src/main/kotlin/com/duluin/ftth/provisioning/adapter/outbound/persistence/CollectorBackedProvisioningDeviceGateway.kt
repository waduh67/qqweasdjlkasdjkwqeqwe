package com.duluin.ftth.provisioning.adapter.outbound.persistence

import com.duluin.ftth.provisioning.application.service.DeviceApplyResult
import com.duluin.ftth.provisioning.application.service.DeviceFailureKind
import com.duluin.ftth.provisioning.application.service.DeviceOperationException
import com.duluin.ftth.provisioning.application.service.DeviceStateObservation
import com.duluin.ftth.provisioning.application.service.DispatchableProvisioningWork
import com.duluin.ftth.provisioning.application.service.ProvisioningDeviceGateway
import com.duluin.ftth.provisioning.domain.model.ExecutionPhase
import com.duluin.ftth.provisioning.domain.model.NormalizedDeviceState
import com.duluin.ftth.provisioning.domain.model.NormalizedField
import com.duluin.ftth.provisioning.domain.model.NormalizedStateHash
import com.duluin.ftth.provisioning.domain.model.NormalizedValue
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration
import java.util.concurrent.locks.LockSupport

@Component
class CollectorBackedProvisioningDeviceGateway(
    private val receipts: CollectorResultReceiptReader,
    private val clock: Clock,
) : ProvisioningDeviceGateway {
    override fun observe(work: DispatchableProvisioningWork): DeviceStateObservation {
        val receipt = await(work)
        val state = receipt.state()
        return DeviceStateObservation(NormalizedStateHash.sha256(state), state, receipt.verificationMatches ?: true)
    }

    override fun apply(work: DispatchableProvisioningWork): DeviceApplyResult {
        val receipt = await(work)
        val state = receipt.state().takeUnless { it == NormalizedDeviceState.empty() } ?: desiredState(work)
        return DeviceApplyResult(NormalizedStateHash.sha256(state), state)
    }

    override fun compensate(work: DispatchableProvisioningWork, before: NormalizedDeviceState): DeviceApplyResult {
        await(work)
        return DeviceApplyResult(NormalizedStateHash.sha256(before), before)
    }

    private fun await(work: DispatchableProvisioningWork): CollectorResultReceipt {
        val timeout = Duration.between(clock.instant(), work.deadline)
        if (timeout.isZero || timeout.isNegative) throw DeviceOperationException("DEADLINE_EXCEEDED", DeviceFailureKind.TRANSIENT)
        val expiresAt = System.nanoTime() + timeout.toNanos()
        while (System.nanoTime() < expiresAt) {
            receipts.find(work.idempotencyKey, work.phase)?.let { receipt ->
                if (!receipt.success) throw DeviceOperationException(
                    receipt.errorCode ?: "COLLECTOR_REJECTED",
                    if (receipt.errorCode in TRANSIENT_ERRORS) DeviceFailureKind.TRANSIENT else DeviceFailureKind.PERMANENT,
                )
                return receipt
            }
            LockSupport.parkNanos(POLL_INTERVAL.toNanos())
            if (Thread.interrupted()) throw InterruptedException("PROVISIONING_GATEWAY_INTERRUPTED")
        }
        throw DeviceOperationException("COLLECTOR_RESULT_TIMEOUT", DeviceFailureKind.TRANSIENT)
    }

    private fun desiredState(work: DispatchableProvisioningWork): NormalizedDeviceState = work.attributes["vlanId"]
        ?.toIntOrNull()
        ?.let { vlan -> NormalizedDeviceState.of(NormalizedField.VLANS to NormalizedValue.sequence(NormalizedValue.number(vlan))) }
        ?: NormalizedDeviceState.empty()

    private companion object {
        val POLL_INTERVAL: Duration = Duration.ofMillis(25)
        val TRANSIENT_ERRORS = setOf("TIMEOUT", "COLLECTOR_RESULT_TIMEOUT", "DEADLINE_EXCEEDED", "CONNECTION_RESET")
    }
}

data class CollectorResultReceipt(
    val success: Boolean,
    val errorCode: String?,
    val verificationMatches: Boolean?,
    val vlanIds: List<Int>,
) {
    fun state(): NormalizedDeviceState = if (vlanIds.isEmpty()) NormalizedDeviceState.empty() else NormalizedDeviceState.of(
        NormalizedField.VLANS to NormalizedValue.Sequence.of(vlanIds.map(NormalizedValue::number)),
    )
}

@Component
class CollectorResultReceiptReader(private val entityManager: EntityManager) {
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    fun find(idempotencyKey: String, phase: ExecutionPhase): CollectorResultReceipt? {
        val wirePhase = when (phase) {
            ExecutionPhase.PREFLIGHT, ExecutionPhase.ROLLBACK_CHECK -> "PREFLIGHT"
            ExecutionPhase.APPLY -> "APPLY"
            ExecutionPhase.VERIFY, ExecutionPhase.ROLLBACK_VERIFY -> "VERIFY"
            ExecutionPhase.COMPENSATE -> "ROLLBACK"
        }
        val row = entityManager.createNativeQuery(
            """SELECT success, error_code, verification_matches, state_vlan_ids
               FROM provisioning_collector_result_receipt
               WHERE idempotency_key = :key AND phase = :phase ORDER BY completed_at DESC LIMIT 1""",
        ).setParameter("key", idempotencyKey).setParameter("phase", wirePhase).resultList.singleOrNull() as? Array<*>
            ?: return null
        return CollectorResultReceipt(
            row[0] as Boolean,
            row[1] as String?,
            row[2] as Boolean?,
            (row[3] as String).split(',').mapNotNull(String::toIntOrNull),
        )
    }
}

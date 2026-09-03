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
        val state = receipt.requiredState()
        val matches = receipt.verificationMatches ?: fail("COLLECTOR_RECEIPT_VERIFICATION_MISSING")
        if (work.phase in setOf(ExecutionPhase.VERIFY, ExecutionPhase.ROLLBACK_VERIFY) && !matches) {
            throw DeviceOperationException("VERIFICATION_MISMATCH", DeviceFailureKind.VERIFICATION_MISMATCH)
        }
        return DeviceStateObservation(NormalizedStateHash.sha256(state), state, matches)
    }

    override fun apply(work: DispatchableProvisioningWork): DeviceApplyResult {
        val receipt = await(work)
        receipt.requireVerified()
        val state = receipt.requiredState()
        return DeviceApplyResult(NormalizedStateHash.sha256(state), state)
    }

    override fun compensate(work: DispatchableProvisioningWork, before: NormalizedDeviceState): DeviceApplyResult {
        val receipt = await(work)
        receipt.requireVerified()
        val state = receipt.requiredState()
        if (state != before) throw DeviceOperationException("ROLLBACK_VERIFICATION_MISMATCH", DeviceFailureKind.VERIFICATION_MISMATCH)
        return DeviceApplyResult(NormalizedStateHash.sha256(state), state)
    }

    private fun await(work: DispatchableProvisioningWork): CollectorResultReceipt {
        val timeout = Duration.between(clock.instant(), work.deadline)
        if (timeout.isZero || timeout.isNegative) throw DeviceOperationException("DEADLINE_EXCEEDED", DeviceFailureKind.TRANSIENT)
        val expiresAt = System.nanoTime() + timeout.toNanos()
        while (System.nanoTime() < expiresAt) {
            receipts.find(work.idempotencyKey, work.phase, work.fencingToken)?.let { receipt ->
                if (receipt.idempotencyKey != work.idempotencyKey || receipt.phase != work.phase.wireName() ||
                    receipt.fencingEpoch != work.fencingToken
                ) throw DeviceOperationException("COLLECTOR_RECEIPT_IDENTITY_MISMATCH", DeviceFailureKind.STALE_PRECONDITION)
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

    private fun CollectorResultReceipt.requireVerified() {
        when (verificationMatches) {
            true -> Unit
            false -> throw DeviceOperationException("VERIFICATION_MISMATCH", DeviceFailureKind.VERIFICATION_MISMATCH)
            null -> fail("COLLECTOR_RECEIPT_VERIFICATION_MISSING")
        }
    }

    private fun fail(code: String): Nothing = throw DeviceOperationException(code, DeviceFailureKind.PERMANENT)

    private companion object {
        val POLL_INTERVAL: Duration = Duration.ofMillis(25)
        val TRANSIENT_ERRORS = setOf("TIMEOUT", "COLLECTOR_RESULT_TIMEOUT", "DEADLINE_EXCEEDED", "CONNECTION_RESET")
    }
}

data class CollectorResultReceipt(
    val idempotencyKey: String,
    val phase: String,
    val fencingEpoch: Long,
    val success: Boolean,
    val errorCode: String?,
    val verificationMatches: Boolean?,
    val vlanIds: List<Int>?,
) {
    fun requiredState(): NormalizedDeviceState = vlanIds?.let { ids -> if (ids.isEmpty()) NormalizedDeviceState.empty() else NormalizedDeviceState.of(
        NormalizedField.VLANS to NormalizedValue.Sequence.of(ids.map(NormalizedValue::number)),
    ) } ?: throw DeviceOperationException("COLLECTOR_RECEIPT_STATE_MISSING", DeviceFailureKind.PERMANENT)
}

@Component
class CollectorResultReceiptReader(private val entityManager: EntityManager) {
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    fun find(idempotencyKey: String, phase: ExecutionPhase, fencingToken: Long): CollectorResultReceipt? {
        val wirePhase = phase.wireName()
        val row = entityManager.createNativeQuery(
            """SELECT idempotency_key, phase, fencing_epoch, success, error_code, verification_matches, state_vlan_ids
               FROM provisioning_collector_result_receipt
               WHERE idempotency_key = :key AND phase = :phase AND fencing_epoch = :fence
               ORDER BY completed_at DESC LIMIT 1""",
        ).setParameter("key", idempotencyKey).setParameter("phase", wirePhase).setParameter("fence", fencingToken)
            .resultList.singleOrNull() as? Array<*>
            ?: return null
        return CollectorResultReceipt(
            row[0] as String,
            row[1] as String,
            (row[2] as Number).toLong(),
            row[3] as Boolean,
            row[4] as String?,
            row[5] as Boolean?,
            parseReceiptVlanIds(row[6] as String?),
        )
    }
}

internal fun parseReceiptVlanIds(value: String?): List<Int>? = value?.let { encoded ->
    if (encoded.isEmpty()) emptyList() else encoded.split(',').map { token ->
        token.toIntOrNull()?.takeIf { it in 1..4094 }
            ?: throw DeviceOperationException("COLLECTOR_RECEIPT_STATE_MALFORMED", DeviceFailureKind.PERMANENT)
    }
}

private fun ExecutionPhase.wireName(): String = when (this) {
    ExecutionPhase.PREFLIGHT, ExecutionPhase.ROLLBACK_CHECK -> "PREFLIGHT"
    ExecutionPhase.APPLY -> "APPLY"
    ExecutionPhase.VERIFY, ExecutionPhase.ROLLBACK_VERIFY -> "VERIFY"
    ExecutionPhase.COMPENSATE -> "ROLLBACK"
}

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
import java.util.UUID
import java.util.concurrent.locks.LockSupport

@Component
class CollectorBackedProvisioningDeviceGateway(
    private val receipts: CollectorResultReceiptReader,
    private val clock: Clock,
) : ProvisioningDeviceGateway {
    override fun observe(work: DispatchableProvisioningWork): DeviceStateObservation {
        val receipt = await(work)
        val state = receipt.requiredState()
        receipt.requireIntegrity(state)
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
        receipt.requireIntegrity(state)
        return DeviceApplyResult(NormalizedStateHash.sha256(state), state)
    }

    override fun compensate(work: DispatchableProvisioningWork, before: NormalizedDeviceState): DeviceApplyResult {
        val receipt = await(work)
        receipt.requireVerified()
        val state = receipt.requiredState()
        receipt.requireIntegrity(state)
        if (state != before) throw DeviceOperationException("ROLLBACK_VERIFICATION_MISMATCH", DeviceFailureKind.VERIFICATION_MISMATCH)
        return DeviceApplyResult(NormalizedStateHash.sha256(state), state)
    }

    private fun await(work: DispatchableProvisioningWork): CollectorResultReceipt {
        val timeout = Duration.between(clock.instant(), work.deadline)
        if (timeout.isZero || timeout.isNegative) throw DeviceOperationException("DEADLINE_EXCEEDED", DeviceFailureKind.TRANSIENT)
        val expiresAt = System.nanoTime() + timeout.toNanos()
        while (System.nanoTime() < expiresAt) {
            receipts.find(work)?.let { receipt ->
                if (!receipt.matches(work)) {
                    throw DeviceOperationException("COLLECTOR_RECEIPT_IDENTITY_MISMATCH", DeviceFailureKind.STALE_PRECONDITION)
                }
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
    val attemptId: UUID,
    val planId: UUID,
    val revision: Int,
    val stepId: UUID,
    val targetId: UUID,
    val operation: com.duluin.ftth.provisioning.domain.model.ProvisionOperation,
    val idempotencyKey: String,
    val phase: String,
    val fencingEpoch: Long,
    val success: Boolean,
    val errorCode: String?,
    val verificationMatches: Boolean?,
    val vlanIds: List<Int>?,
    val managedResourceCount: Int?,
    val reportedStateHash: String?,
) {
    fun matches(work: DispatchableProvisioningWork): Boolean =
        attemptId == work.attemptId && planId == work.planId && revision == work.revision && stepId == work.stepId &&
            targetId == work.device.id && operation == work.operation && idempotencyKey == work.idempotencyKey &&
            phase == work.phase.wireName() && fencingEpoch == work.fencingToken

    fun requiredState(): NormalizedDeviceState {
        val ids = vlanIds ?: throw DeviceOperationException("COLLECTOR_RECEIPT_STATE_MISSING", DeviceFailureKind.PERMANENT)
        val count = managedResourceCount
            ?: throw DeviceOperationException("COLLECTOR_RECEIPT_RESOURCE_COUNT_MISSING", DeviceFailureKind.PERMANENT)
        return NormalizedDeviceState.of(
            NormalizedField.MANAGED_RESOURCE_COUNT to NormalizedValue.number(count),
            NormalizedField.VLANS to NormalizedValue.Sequence.of(ids.map(NormalizedValue::number)),
        )
    }

    fun requireIntegrity(state: NormalizedDeviceState) {
        val hash = reportedStateHash
            ?: throw DeviceOperationException("COLLECTOR_RECEIPT_STATE_HASH_MISSING", DeviceFailureKind.PERMANENT)
        val count = (state.values[NormalizedField.MANAGED_RESOURCE_COUNT] as NormalizedValue.Number).value.toInt()
        val ids = (state.values[NormalizedField.VLANS] as NormalizedValue.Sequence).values
            .map { (it as NormalizedValue.Number).value.toInt() }
        val canonical = "managedResourceCount=$count;vlanIds=${ids.joinToString(",")}"
        val computed = java.security.MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(java.nio.charset.StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        if (computed != hash) throw DeviceOperationException("COLLECTOR_RECEIPT_STATE_HASH_MISMATCH", DeviceFailureKind.PERMANENT)
    }
}

@Component
class CollectorResultReceiptReader(private val entityManager: EntityManager) {
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    fun find(work: DispatchableProvisioningWork): CollectorResultReceipt? {
        val wirePhase = work.phase.wireName()
        val row = entityManager.createNativeQuery(
            """SELECT attempt_id, plan_id, revision, step_id, target_id, operation_class,
                      idempotency_key, phase, fencing_epoch, success, error_code, verification_matches, state_vlan_ids,
                      managed_resource_count, preflight_hash, apply_state_hash, verification_state_hash, rollback_state_hash
               FROM provisioning_collector_result_receipt
               WHERE attempt_id = :attempt AND idempotency_key = :key AND plan_id = :plan AND revision = :revision
                 AND step_id = :step AND target_id = :target AND operation_class = :operation
                 AND phase = :phase AND fencing_epoch = :fence
               ORDER BY completed_at DESC LIMIT 1""",
        ).setParameter("attempt", work.attemptId).setParameter("key", work.idempotencyKey)
            .setParameter("plan", work.planId.toString()).setParameter("revision", work.revision)
            .setParameter("step", work.stepId.toString()).setParameter("target", work.device.id.toString())
            .setParameter("operation", work.operation.name).setParameter("phase", wirePhase)
            .setParameter("fence", work.fencingToken)
            .resultList.singleOrNull() as? Array<*>
            ?: return null
        return CollectorResultReceipt(
            row[0] as UUID,
            UUID.fromString(row[1] as String),
            (row[2] as Number).toInt(),
            UUID.fromString(row[3] as String),
            UUID.fromString(row[4] as String),
            com.duluin.ftth.provisioning.domain.model.ProvisionOperation.valueOf(row[5] as String),
            row[6] as String,
            row[7] as String,
            (row[8] as Number).toLong(),
            row[9] as Boolean,
            row[10] as String?,
            row[11] as Boolean?,
            parseReceiptVlanIds(row[12] as String?),
            (row[13] as Number?)?.toInt(),
            when (row[7] as String) {
                "PREFLIGHT" -> row[14] as String?
                "APPLY" -> row[15] as String?
                "VERIFY" -> row[16] as String?
                "ROLLBACK" -> row[17] as String?
                else -> null
            },
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

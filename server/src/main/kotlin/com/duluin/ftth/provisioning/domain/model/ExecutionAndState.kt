package com.duluin.ftth.provisioning.domain.model

import com.duluin.ftth.common.domain.UuidV7
import com.duluin.ftth.common.domain.error.ConflictException
import com.duluin.ftth.common.domain.error.ValidationException
import com.duluin.ftth.provisioning.domain.policy.CertificationStatus
import java.time.Instant
import java.util.UUID

class ProvisionExecution private constructor(
    override val id: UUID,
    val tenantId: UUID,
    val intentId: UUID,
    val planId: UUID,
    val idempotencyKey: String,
    status: ExecutionStatus,
    detail: String?,
) : ProvisioningAggregate {
    var status: ExecutionStatus = status
        private set
    var detail: String? = detail
        private set

    init {
        if (idempotencyKey.isBlank() || idempotencyKey.length > 160) {
            throw ValidationException("EXECUTION_IDEMPOTENCY_KEY_INVALID")
        }
    }

    fun start() = transitionTo(ExecutionStatus.RUNNING, setOf(ExecutionStatus.QUEUED))
    fun verify() = transitionTo(ExecutionStatus.VERIFYING, setOf(ExecutionStatus.RUNNING))
    fun succeed() = transitionTo(ExecutionStatus.SUCCEEDED, setOf(ExecutionStatus.VERIFYING))
    fun cancel() = transitionTo(ExecutionStatus.CANCELLED, setOf(ExecutionStatus.QUEUED))
    fun beginRollback() = transitionTo(
        ExecutionStatus.ROLLING_BACK,
        setOf(ExecutionStatus.RUNNING, ExecutionStatus.VERIFYING),
    )
    fun completeRollback() = transitionTo(ExecutionStatus.ROLLED_BACK, setOf(ExecutionStatus.ROLLING_BACK))
    fun fail(detail: String) = transitionWithDetail(
        ExecutionStatus.FAILED,
        setOf(ExecutionStatus.QUEUED, ExecutionStatus.RUNNING, ExecutionStatus.VERIFYING, ExecutionStatus.ROLLING_BACK),
        detail,
    )
    fun requireManualReconciliation(detail: String) = transitionWithDetail(
        ExecutionStatus.MANUAL_RECONCILIATION,
        setOf(ExecutionStatus.RUNNING, ExecutionStatus.VERIFYING, ExecutionStatus.ROLLING_BACK, ExecutionStatus.FAILED),
        detail,
    )

    private fun transitionWithDetail(next: ExecutionStatus, allowed: Set<ExecutionStatus>, detail: String) {
        if (detail.isBlank()) throw ValidationException("EXECUTION_DETAIL_REQUIRED")
        transitionTo(next, allowed)
        this.detail = detail
    }

    private fun transitionTo(next: ExecutionStatus, allowed: Set<ExecutionStatus>) {
        if (status !in allowed) throw ConflictException("ILLEGAL_EXECUTION_TRANSITION: $status -> $next")
        status = next
    }

    companion object {
        fun queue(tenantId: UUID, planId: UUID, idempotencyKey: String) =
            queue(tenantId, planId, planId, idempotencyKey)

        fun queue(tenantId: UUID, intentId: UUID, planId: UUID, idempotencyKey: String) = ProvisionExecution(
            UuidV7.generate(), tenantId, intentId, planId, idempotencyKey.trim(), ExecutionStatus.QUEUED, null,
        )

        fun rehydrate(
            id: UUID,
            tenantId: UUID,
            intentId: UUID,
            planId: UUID,
            idempotencyKey: String,
            status: ExecutionStatus,
            detail: String?,
        ) = ProvisionExecution(id, tenantId, intentId, planId, idempotencyKey, status, detail)

        fun rehydrate(
            id: UUID,
            tenantId: UUID,
            planId: UUID,
            idempotencyKey: String,
            status: ExecutionStatus,
            detail: String?,
        ) = rehydrate(id, tenantId, planId, planId, idempotencyKey, status, detail)
    }
}

class DeviceSnapshot private constructor(
    override val id: UUID,
    val tenantId: UUID,
    val device: DeviceReference,
    val planId: UUID,
    val state: NormalizedDeviceState,
    val capturedAt: Instant,
) : ProvisioningAggregate {
    companion object {
        fun capture(tenantId: UUID, device: DeviceReference, planId: UUID, state: NormalizedDeviceState) =
            DeviceSnapshot(UuidV7.generate(), tenantId, device, planId, state, Instant.now())

        fun rehydrate(
            id: UUID,
            tenantId: UUID,
            device: DeviceReference,
            planId: UUID,
            state: NormalizedDeviceState,
            capturedAt: Instant,
        ) = DeviceSnapshot(id, tenantId, device, planId, state, capturedAt)
    }
}

class DeviceObservation private constructor(
    override val id: UUID,
    val tenantId: UUID,
    val device: DeviceReference,
    val state: NormalizedDeviceState,
    val observedAt: Instant,
) : ProvisioningAggregate {
    companion object {
        fun record(tenantId: UUID, device: DeviceReference, state: NormalizedDeviceState) =
            DeviceObservation(UuidV7.generate(), tenantId, device, state, Instant.now())

        fun rehydrate(
            id: UUID,
            tenantId: UUID,
            device: DeviceReference,
            state: NormalizedDeviceState,
            observedAt: Instant,
        ) = DeviceObservation(id, tenantId, device, state, observedAt)
    }
}

class DriftRecord private constructor(
    override val id: UUID,
    val tenantId: UUID,
    val device: DeviceReference,
    val snapshotId: UUID,
    val observationId: UUID,
    val status: DriftStatus,
    val recordedAt: Instant,
) : ProvisioningAggregate {
    companion object {
        fun record(
            tenantId: UUID,
            device: DeviceReference,
            snapshotId: UUID,
            observationId: UUID,
            status: DriftStatus,
        ) = DriftRecord(UuidV7.generate(), tenantId, device, snapshotId, observationId, status, Instant.now())

        fun rehydrate(
            id: UUID,
            tenantId: UUID,
            device: DeviceReference,
            snapshotId: UUID,
            observationId: UUID,
            status: DriftStatus,
            recordedAt: Instant,
        ) = DriftRecord(id, tenantId, device, snapshotId, observationId, status, recordedAt)
    }
}

class AdapterCertification private constructor(
    override val id: UUID,
    val tenantId: UUID,
    val device: DeviceReference,
    val vendor: String,
    val model: String,
    val firmware: String,
    val transport: String,
    val operationClass: String,
    val status: CertificationStatus,
    val validUntil: Instant,
    val evidenceId: UUID?,
    val certifiedBy: UUID,
    val certifiedAt: Instant,
    revokedAt: Instant?,
    revokedBy: UUID?,
) : ProvisioningAggregate {
    var revokedAt: Instant? = revokedAt
        private set
    var revokedBy: UUID? = revokedBy
        private set
    val active: Boolean get() = revokedAt == null

    fun revoke(actorId: UUID, at: Instant = Instant.now()) {
        if (revokedAt != null) throw ConflictException("CERTIFICATION_ALREADY_REVOKED")
        revokedAt = at
        revokedBy = actorId
    }

    companion object {
        fun certify(
            tenantId: UUID,
            device: DeviceReference,
            vendor: String,
            model: String,
            firmware: String,
            transport: String,
            operationClass: String,
            status: CertificationStatus,
            validUntil: Instant,
            evidenceId: UUID,
            certifiedBy: UUID,
            certifiedAt: Instant = Instant.now(),
        ): AdapterCertification {
            val fingerprint = listOf(vendor, model, firmware, transport, operationClass)
            if (fingerprint.any { it.isBlank() || it.length > 120 }) {
                throw ValidationException("CERTIFICATION_FINGERPRINT_INVALID")
            }
            if (!validUntil.isAfter(certifiedAt)) throw ValidationException("CERTIFICATION_VALIDITY_INVALID")
            return AdapterCertification(
                UuidV7.generate(), tenantId, device, vendor, model, firmware, transport, operationClass,
                status, validUntil, evidenceId, certifiedBy, certifiedAt, null, null,
            )
        }

        fun certify(
            tenantId: UUID,
            device: DeviceReference,
            model: String,
            firmware: String,
            transport: String,
            operationClass: String,
        ): AdapterCertification {
            val now = Instant.now()
            val id = UuidV7.generate()
            return AdapterCertification(
                id, tenantId, device, "UNKNOWN", model, firmware, transport, operationClass,
                CertificationStatus.PROVISIONAL, now.plusNanos(1), null, id, now, null, null,
            )
        }

        fun rehydrate(
            id: UUID,
            tenantId: UUID,
            device: DeviceReference,
            vendor: String,
            model: String,
            firmware: String,
            transport: String,
            operationClass: String,
            status: CertificationStatus,
            validUntil: Instant,
            evidenceId: UUID?,
            certifiedBy: UUID,
            certifiedAt: Instant,
            revokedAt: Instant?,
            revokedBy: UUID?,
        ) = AdapterCertification(
            id, tenantId, device, vendor, model, firmware, transport, operationClass, status, validUntil,
            evidenceId, certifiedBy, certifiedAt, revokedAt, revokedBy,
        )
    }
}

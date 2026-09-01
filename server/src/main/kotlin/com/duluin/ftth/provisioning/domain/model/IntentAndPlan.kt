package com.duluin.ftth.provisioning.domain.model

import com.duluin.ftth.common.domain.UuidV7
import com.duluin.ftth.common.domain.error.ConflictException
import com.duluin.ftth.common.domain.error.ValidationException
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID

class ServiceIntent private constructor(
    override val id: UUID,
    val tenantId: UUID,
    val subscriptionId: UUID,
    val segmentProfileId: UUID,
    val encapsulation: VlanEncapsulation,
    val dedicatedVlanId: Int?,
    status: IntentStatus,
) : ProvisioningAggregate {
    var status: IntentStatus = status
        private set

    init {
        if (encapsulation != VlanEncapsulation.SINGLE_TAG) throw ValidationException("UNSUPPORTED_VLAN_MODE")
        dedicatedVlanId?.let {
            if (it !in 2..4094) throw ValidationException("VLAN_ID_OUT_OF_RANGE")
        }
    }

    fun activate() = transitionTo(IntentStatus.ACTIVE, setOf(IntentStatus.DRAFT, IntentStatus.SUSPENDED))
    fun suspend() = transitionTo(IntentStatus.SUSPENDED, setOf(IntentStatus.ACTIVE))
    fun decommission() = transitionTo(
        IntentStatus.DECOMMISSIONED,
        setOf(IntentStatus.DRAFT, IntentStatus.ACTIVE, IntentStatus.SUSPENDED),
    )

    private fun transitionTo(next: IntentStatus, allowed: Set<IntentStatus>) {
        if (status !in allowed) throw ConflictException("ILLEGAL_INTENT_TRANSITION: $status -> $next")
        status = next
    }

    companion object {
        fun create(
            tenantId: UUID,
            subscriptionId: UUID,
            segmentProfileId: UUID,
            encapsulation: VlanEncapsulation = VlanEncapsulation.SINGLE_TAG,
            dedicatedVlanId: Int? = null,
        ) = ServiceIntent(
            UuidV7.generate(), tenantId, subscriptionId, segmentProfileId, encapsulation, dedicatedVlanId, IntentStatus.DRAFT,
        )

        fun rehydrate(
            id: UUID,
            tenantId: UUID,
            subscriptionId: UUID,
            segmentProfileId: UUID,
            encapsulation: VlanEncapsulation,
            dedicatedVlanId: Int?,
            status: IntentStatus,
        ) = ServiceIntent(id, tenantId, subscriptionId, segmentProfileId, encapsulation, dedicatedVlanId, status)
    }
}

class ProvisionStep private constructor(
    override val id: UUID,
    val order: Int,
    val device: DeviceReference,
    val operation: ProvisionOperation,
    val attributes: Map<String, String>,
) : ProvisioningAggregate {
    init {
        if (order < 1) throw ValidationException("PROVISION_STEP_ORDER_INVALID")
        NormalizedDeviceState.of(attributes)
    }

    internal fun canonical(): String = buildString {
        append(order).append('|').append(device.kind).append('|').append(device.id).append('|').append(operation)
        attributes.toSortedMap().forEach { (key, value) -> append('|').append(key).append('=').append(value) }
    }

    companion object {
        fun create(
            order: Int,
            device: DeviceReference,
            operation: ProvisionOperation,
            attributes: Map<String, String>,
        ) = ProvisionStep(UuidV7.generate(), order, device, operation, attributes.toMap())

        fun rehydrate(
            id: UUID,
            order: Int,
            device: DeviceReference,
            operation: ProvisionOperation,
            attributes: Map<String, String>,
        ) = ProvisionStep(id, order, device, operation, attributes)
    }
}

class ProvisionPlan private constructor(
    override val id: UUID,
    val tenantId: UUID,
    val intentId: UUID,
    val revision: Int,
    steps: List<ProvisionStep>,
    status: PlanStatus,
    contentHash: String,
) : ProvisioningAggregate {
    private var mutableSteps = steps.toList()
    val steps: List<ProvisionStep> get() = mutableSteps
    var status: PlanStatus = status
        private set
    var contentHash: String = contentHash
        private set

    init {
        if (revision < 1) throw ValidationException("PLAN_REVISION_INVALID")
        requireValidSteps(steps)
        if (!contentHash.matches(Regex("^[a-f0-9]{64}$"))) throw ValidationException("PLAN_CONTENT_HASH_INVALID")
    }

    fun replaceSteps(steps: List<ProvisionStep>) {
        if (status != PlanStatus.GENERATED) throw ConflictException("PLAN_IMMUTABLE")
        requireValidSteps(steps)
        mutableSteps = steps.toList()
        contentHash = hash(steps)
    }

    fun validate() = transitionTo(PlanStatus.VALIDATED, setOf(PlanStatus.GENERATED))
    fun reject() = transitionTo(PlanStatus.REJECTED, setOf(PlanStatus.GENERATED))
    fun supersede() = transitionTo(PlanStatus.SUPERSEDED, setOf(PlanStatus.VALIDATED))

    private fun transitionTo(next: PlanStatus, allowed: Set<PlanStatus>) {
        if (status !in allowed) throw ConflictException("ILLEGAL_PLAN_TRANSITION: $status -> $next")
        status = next
    }

    companion object {
        fun generate(tenantId: UUID, intentId: UUID, revision: Int, steps: List<ProvisionStep>) =
            ProvisionPlan(UuidV7.generate(), tenantId, intentId, revision, steps, PlanStatus.GENERATED, hash(steps))

        fun rehydrate(
            id: UUID,
            tenantId: UUID,
            intentId: UUID,
            revision: Int,
            steps: List<ProvisionStep>,
            status: PlanStatus,
            contentHash: String,
        ) = ProvisionPlan(id, tenantId, intentId, revision, steps, status, contentHash)

        private fun requireValidSteps(steps: List<ProvisionStep>) {
            if (steps.isEmpty()) throw ValidationException("PLAN_STEPS_EMPTY")
            if (steps.map { it.order }.toSet().size != steps.size) throw ValidationException("PLAN_STEP_ORDER_DUPLICATE")
        }

        private fun hash(steps: List<ProvisionStep>): String {
            val canonical = steps.sortedBy { it.order }.joinToString("\n", transform = ProvisionStep::canonical)
            return MessageDigest.getInstance("SHA-256")
                .digest(canonical.toByteArray(StandardCharsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
        }
    }
}

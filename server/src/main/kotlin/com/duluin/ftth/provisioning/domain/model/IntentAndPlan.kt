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
    attributes: Map<String, String>,
    strictAttributes: Boolean,
) : ProvisioningAggregate {
    val attributes: Map<String, String> = attributes.toMap()
    val preconditionHash: String
        get() = attributes[PRECONDITION_HASH_ATTRIBUTE] ?: EMPTY_PRECONDITION_HASH

    init {
        if (order < 1) throw ValidationException("PROVISION_STEP_ORDER_INVALID")
        if (strictAttributes) PlanAttributePolicy.validate(attributes) else PlanAttributePolicy.validateLegacy(attributes)
    }

    internal fun canonical(): String = buildString {
        listOf(id.toString(), order.toString(), device.kind.name, device.id.toString(), operation.name)
            .forEach { append(LengthPrefixedCanonical.encode(it)) }
        attributes.entries.sortedWith { left, right -> Utf8ByteComparator.compare(left.key, right.key) }.forEach { (key, value) ->
            append(LengthPrefixedCanonical.encode(key))
            append(LengthPrefixedCanonical.encode(value))
        }
    }

    companion object {
        const val PRECONDITION_HASH_ATTRIBUTE = "expectedPreconditionHash"
        private val EMPTY_PRECONDITION_HASH = sha256("")

        fun create(
            order: Int,
            device: DeviceReference,
            operation: ProvisionOperation,
            attributes: Map<String, String>,
        ) = ProvisionStep(UuidV7.generate(), order, device, operation, attributes.toMap(), true)

        fun rehydrate(
            id: UUID,
            order: Int,
            device: DeviceReference,
            operation: ProvisionOperation,
            attributes: Map<String, String>,
        ) = ProvisionStep(id, order, device, operation, attributes, false)

        fun compile(
            id: UUID,
            order: Int,
            device: DeviceReference,
            operation: ProvisionOperation,
            attributes: Map<String, String>,
        ) = ProvisionStep(id, order, device, operation, attributes, true)

        private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
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
    val steps: List<ProvisionStep> = steps.toList()
    var status: PlanStatus = status
        private set
    val contentHash: String = contentHash
    val preconditionHash: String
        get() = steps.firstNotNullOfOrNull { it.attributes[PLAN_PRECONDITION_HASH_ATTRIBUTE] } ?: contentHash

    init {
        if (revision < 1) throw ValidationException("PLAN_REVISION_INVALID")
        requireValidSteps(steps)
        if (!contentHash.matches(Regex("^[a-f0-9]{64}$"))) throw ValidationException("PLAN_CONTENT_HASH_INVALID")
        if (contentHash != hash(steps)) throw ValidationException("PLAN_CONTENT_HASH_MISMATCH")
    }

    fun validate() = transitionTo(PlanStatus.VALIDATED, setOf(PlanStatus.GENERATED))
    fun reject() = transitionTo(PlanStatus.REJECTED, setOf(PlanStatus.GENERATED))
    fun supersede() = transitionTo(PlanStatus.SUPERSEDED, setOf(PlanStatus.VALIDATED))

    fun canonicalPayload(): String = buildString {
        append(LengthPrefixedCanonical.encode(
            listOf(id.toString(), tenantId.toString(), intentId.toString(), revision.toString(), preconditionHash, contentHash),
        ))
        steps.sortedBy { it.order }.forEach { append(LengthPrefixedCanonical.encode(it.canonical())) }
    }

    private fun transitionTo(next: PlanStatus, allowed: Set<PlanStatus>) {
        if (status !in allowed) throw ConflictException("ILLEGAL_PLAN_TRANSITION: $status -> $next")
        status = next
    }

    companion object {
        const val PLAN_PRECONDITION_HASH_ATTRIBUTE = "planPreconditionHash"

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

        fun compile(
            id: UUID,
            tenantId: UUID,
            intentId: UUID,
            revision: Int,
            steps: List<ProvisionStep>,
        ) = ProvisionPlan(id, tenantId, intentId, revision, steps, PlanStatus.GENERATED, hash(steps))

        private fun requireValidSteps(steps: List<ProvisionStep>) {
            if (steps.isEmpty()) throw ValidationException("PLAN_STEPS_EMPTY")
            if (steps.map { it.order }.toSet().size != steps.size) throw ValidationException("PLAN_STEP_ORDER_DUPLICATE")
        }

        private fun hash(steps: List<ProvisionStep>): String {
            val canonical = steps.sortedBy { it.order }
                .joinToString(separator = "") { LengthPrefixedCanonical.encode(it.canonical()) }
            return MessageDigest.getInstance("SHA-256")
                .digest(canonical.toByteArray(StandardCharsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
        }
    }
}

internal object LengthPrefixedCanonical {
    fun encode(value: String): String = "${value.toByteArray(StandardCharsets.UTF_8).size}:$value"
    fun encode(values: List<String>): String = values.joinToString(separator = "", transform = ::encode)
}

internal object Utf8ByteComparator {
    fun compare(left: String, right: String): Int {
        val leftBytes = left.toByteArray(StandardCharsets.UTF_8)
        val rightBytes = right.toByteArray(StandardCharsets.UTF_8)
        for (index in 0 until minOf(leftBytes.size, rightBytes.size)) {
            val comparison = (leftBytes[index].toInt() and 0xff).compareTo(rightBytes[index].toInt() and 0xff)
            if (comparison != 0) return comparison
        }
        return leftBytes.size.compareTo(rightBytes.size)
    }
}

private object PlanAttributePolicy {
    private val allowedKeys = setOf(
        "intentId",
        "vlanId",
        ProvisionStep.PRECONDITION_HASH_ATTRIBUTE,
        ProvisionPlan.PLAN_PRECONDITION_HASH_ATTRIBUTE,
        "interface",
        "safety.vendor",
        "safety.model",
        "safety.firmware",
        "safety.transport",
    )
    private val forbiddenValueFragments = setOf(
        "password", "secret", "credential", "token", "privatekey", "rawcli", "command", "script",
        "-----begin", "/interface ", "configure terminal",
    )
    private val canonicalUuid = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")
    private val hash = Regex("^[a-f0-9]{64}$")
    private val interfaceName = Regex("^[A-Za-z0-9._:/-]{1,160}$")

    fun validate(attributes: Map<String, String>) {
        validateSafeContent(attributes)
        attributes.forEach { (key, value) ->
            if (key !in allowedKeys) {
                throw ValidationException("PLAN_ATTRIBUTE_UNSUPPORTED: $key")
            }
            val valid = when (key) {
                "intentId" -> canonicalUuid.matches(value)
                "vlanId" -> value.matches(Regex("^[0-9]{1,4}$")) && value.toInt() in 2..4094
                ProvisionStep.PRECONDITION_HASH_ATTRIBUTE, ProvisionPlan.PLAN_PRECONDITION_HASH_ATTRIBUTE -> hash.matches(value)
                "interface", "safety.vendor", "safety.model", "safety.firmware", "safety.transport" ->
                    interfaceName.matches(value)
                else -> false
            }
            if (!valid) throw ValidationException("PLAN_ATTRIBUTE_VALUE_INVALID: $key")
        }
    }

    fun validateLegacy(attributes: Map<String, String>) {
        validateSafeContent(attributes)
        attributes.keys.forEach { key ->
            val normalizedKey = key.lowercase().filter(Char::isLetterOrDigit)
            if (key.isBlank() || key.length > 80 || forbiddenValueFragments.any(normalizedKey::contains)) {
                throw ValidationException("SENSITIVE_FIELD: legacy plan attribute is not safe")
            }
        }
    }

    private fun validateSafeContent(attributes: Map<String, String>) {
        attributes.values.forEach { value ->
            val normalizedValue = value.lowercase()
            if (value.length > 500 || value.any(Char::isISOControl) || forbiddenValueFragments.any(normalizedValue::contains)) {
                throw ValidationException("SENSITIVE_VALUE: plan attribute value is not normalized")
            }
        }
    }
}

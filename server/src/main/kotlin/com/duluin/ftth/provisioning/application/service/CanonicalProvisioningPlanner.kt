package com.duluin.ftth.provisioning.application.service

import com.duluin.ftth.common.domain.error.ValidationException
import com.duluin.ftth.provisioning.application.port.outbound.ProvisionPlanRepository
import com.duluin.ftth.provisioning.domain.model.AdministrativeStatus
import com.duluin.ftth.provisioning.domain.model.DeviceKind
import com.duluin.ftth.provisioning.domain.model.DeviceReference
import com.duluin.ftth.provisioning.domain.model.ManagedNodeRole
import com.duluin.ftth.provisioning.domain.model.NormalizedDeviceState
import com.duluin.ftth.provisioning.domain.model.NormalizedStateHash
import com.duluin.ftth.provisioning.domain.model.PlanStatus
import com.duluin.ftth.provisioning.domain.model.ProvisionOperation
import com.duluin.ftth.provisioning.domain.model.ProvisionPlan
import com.duluin.ftth.provisioning.domain.model.ProvisionStep
import com.duluin.ftth.provisioning.domain.model.ServiceIntent
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

enum class PlanChange { CREATE, DELETE }

data class PlanTopologyNode(
    val device: DeviceReference,
    val role: ManagedNodeRole,
    val administrativeStatus: AdministrativeStatus,
    val observedAt: Instant,
    val interfaceName: String,
)

data class PlanCapability(
    val device: DeviceReference,
    val vendor: String,
    val model: String,
    val firmware: String,
    val transport: String,
    val capabilities: Set<String>,
    val observedAt: Instant,
) {
    init {
        require(listOf(vendor, model, firmware, transport).none(String::isBlank)) { "PLAN_FINGERPRINT_INCOMPLETE" }
    }
}

data class PlanObservation(
    val device: DeviceReference,
    val state: NormalizedDeviceState,
    val observedAt: Instant,
)

data class PlanCompilationRequest(
    val intent: ServiceIntent,
    val vlanId: Int,
    val change: PlanChange,
    val topology: List<PlanTopologyNode>,
    val capabilities: List<PlanCapability>,
    val observations: List<PlanObservation>,
    val brasReferenceCount: Int,
)

@Component
class CanonicalProvisioningPlanner {
    fun compile(request: PlanCompilationRequest, revision: Int): ProvisionPlan {
        validate(request)
        val sourceHash = sha256(canonicalRequest(request))
        val observations = request.observations.associateBy { it.device }
        val capabilities = request.capabilities.associateBy { it.device }
        val specifications = when (request.change) {
            PlanChange.CREATE -> createSteps(request.topology)
            PlanChange.DELETE -> deleteSteps(request.topology, request.brasReferenceCount)
        }
        val steps = specifications.mapIndexed { index, (device, operation) ->
            val order = index + 1
            val preconditionHash = NormalizedStateHash.sha256(observations.getValue(device).state)
            val attributes = sortedMapOf(
                "intentId" to request.intent.id.toString(),
                "vlanId" to request.vlanId.toString(),
                ProvisionStep.PRECONDITION_HASH_ATTRIBUTE to preconditionHash,
                ProvisionPlan.PLAN_PRECONDITION_HASH_ATTRIBUTE to sourceHash,
                SafetyPlanAttributes.VENDOR to capabilities.getValue(device).vendor,
                SafetyPlanAttributes.MODEL to capabilities.getValue(device).model,
                SafetyPlanAttributes.FIRMWARE to capabilities.getValue(device).firmware,
                SafetyPlanAttributes.TRANSPORT to capabilities.getValue(device).transport,
            )
            val identity = listOf(request.intent.id, revision, order, device.kind, device.id, operation, attributes)
                .joinToString("|")
            ProvisionStep.compile(deterministicUuid(identity), order, device, operation, attributes)
        }
        val planIdentity = "${request.tenantId()}|${request.intent.id}|$revision|$sourceHash"
        return ProvisionPlan.compile(
            deterministicUuid(planIdentity),
            request.tenantId(),
            request.intent.id,
            revision,
            steps,
        )
    }

    private fun validate(request: PlanCompilationRequest) {
        if (request.vlanId !in 2..4094) throw ValidationException("VLAN_ID_OUT_OF_RANGE")
        if (request.brasReferenceCount < 0) throw ValidationException("BRAS_REFERENCE_COUNT_INVALID")
        if (request.topology.size < 2 || request.topology.first().role != ManagedNodeRole.OLT ||
            request.topology.last().role != ManagedNodeRole.BRAS
        ) {
            throw ValidationException("PLANNING_PATH_INVALID")
        }
        if (request.topology.any { it.administrativeStatus != AdministrativeStatus.ENABLED }) {
            throw ValidationException("PLANNING_PATH_DISABLED")
        }
        val devices = request.topology.map { it.device }
        if (devices.toSet().size != devices.size) throw ValidationException("PLANNING_PATH_CYCLIC")
        if (request.capabilities.map { it.device }.toSet() != devices.toSet()) {
            throw ValidationException("PLANNING_CAPABILITIES_INCOMPLETE")
        }
        if (request.observations.map { it.device }.toSet() != devices.toSet()) {
            throw ValidationException("PLANNING_OBSERVATIONS_INCOMPLETE")
        }
    }

    private fun createSteps(topology: List<PlanTopologyNode>): List<Pair<DeviceReference, ProvisionOperation>> =
        topology.asReversed().map { node ->
            node.device to when (node.role) {
                ManagedNodeRole.BRAS -> ProvisionOperation.ENSURE_PPPOE_TERMINATION
                ManagedNodeRole.OLT -> ProvisionOperation.ENSURE_ACCESS_PORT
                ManagedNodeRole.ACCESS_SWITCH, ManagedNodeRole.AGGREGATION_SWITCH -> ProvisionOperation.ENSURE_TAGGED_VLAN
            }
        }

    private fun deleteSteps(
        topology: List<PlanTopologyNode>,
        brasReferenceCount: Int,
    ): List<Pair<DeviceReference, ProvisionOperation>> = buildList {
        add(topology.last().device to ProvisionOperation.BLOCK_PPPOE_SESSIONS)
        add(topology.first().device to ProvisionOperation.REMOVE_ACCESS_PORT)
        topology.drop(1).dropLast(1).forEach { add(it.device to ProvisionOperation.REMOVE_TAGGED_VLAN) }
        if (brasReferenceCount == 0) add(topology.last().device to ProvisionOperation.REMOVE_PPPOE_TERMINATION)
    }

    private fun canonicalRequest(request: PlanCompilationRequest): String = buildString {
        append("intent=").append(canonicalValue(mapOf(
            "id" to request.intent.id.toString(),
            "tenantId" to request.intent.tenantId.toString(),
            "subscriptionId" to request.intent.subscriptionId.toString(),
            "segmentProfileId" to request.intent.segmentProfileId.toString(),
            "encapsulation" to request.intent.encapsulation.name,
            "dedicatedVlanId" to request.intent.dedicatedVlanId,
            "status" to request.intent.status.name,
        )))
        append("|vlan=").append(request.vlanId).append("|change=").append(request.change)
        append("|references=").append(request.brasReferenceCount)
        append("|topology=").append(canonicalValue(request.topology.map {
            mapOf(
                "deviceKind" to it.device.kind.name,
                "deviceId" to it.device.id.toString(),
                "role" to it.role.name,
                "status" to it.administrativeStatus.name,
                "observedAt" to it.observedAt.toString(),
                "interface" to it.interfaceName,
            )
        }))
        append("|capabilities=").append(canonicalValue(request.capabilities.map {
            mapOf(
                "deviceKind" to it.device.kind.name,
                "deviceId" to it.device.id.toString(),
                "vendor" to it.vendor,
                "model" to it.model,
                "firmware" to it.firmware,
                "transport" to it.transport,
                "capabilities" to it.capabilities,
                "observedAt" to it.observedAt.toString(),
            )
        }.sortedBy { it["deviceId"].toString() }))
        append("|observations=").append(canonicalValue(request.observations.map {
            mapOf(
                "deviceKind" to it.device.kind.name,
                "deviceId" to it.device.id.toString(),
                "state" to it.state.canonicalForm(),
                "observedAt" to it.observedAt.toString(),
            )
        }.sortedBy { it["deviceId"].toString() }))
    }

    private fun PlanCompilationRequest.tenantId(): UUID = intent.tenantId

    private fun canonicalValue(value: Any?): String = when (value) {
        null -> "null"
        is Map<*, *> -> value.entries.sortedBy { it.key.toString() }
            .joinToString(prefix = "{", postfix = "}") { canonicalString(it.key.toString()) + ":" + canonicalValue(it.value) }
        is Set<*> -> value.map(::canonicalValue).sorted().joinToString(prefix = "[", postfix = "]")
        is Iterable<*> -> value.joinToString(prefix = "[", postfix = "]", transform = ::canonicalValue)
        is Array<*> -> value.joinToString(prefix = "[", postfix = "]", transform = ::canonicalValue)
        is Boolean, is Number -> value.toString()
        else -> canonicalString(value.toString())
    }

    private fun canonicalString(value: String): String = "${value.toByteArray(StandardCharsets.UTF_8).size}:$value"

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private fun deterministicUuid(value: String): UUID {
        val bytes = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(StandardCharsets.UTF_8)).copyOf(16)
        bytes[6] = (bytes[6].toInt() and 0x0f or 0x50).toByte()
        bytes[8] = (bytes[8].toInt() and 0x3f or 0x80).toByte()
        val buffer = ByteBuffer.wrap(bytes)
        return UUID(buffer.long, buffer.long)
    }
}

@Service
class ImmutableProvisioningPlanService(
    private val planner: CanonicalProvisioningPlanner,
    private val plans: ProvisionPlanRepository,
    private val safetyGate: ProvisioningSafetyGate,
) {
    @Transactional
    fun plan(request: PlanCompilationRequest): ProvisionPlan {
        val current = plans.findLatestByIntentId(request.intent.id)
        val comparison = planner.compile(request, current?.revision ?: 1)
        if (current != null && current.preconditionHash == comparison.preconditionHash) {
            safetyGate.requireAllowed(current, com.duluin.ftth.provisioning.domain.policy.ExecutionMode.PRODUCTION_AUTO_APPLY)
            if (current.status == PlanStatus.GENERATED) current.validate()
            return plans.save(current)
        }

        val candidate = planner.compile(request, (current?.revision ?: 0) + 1)
        safetyGate.requireAllowed(candidate, com.duluin.ftth.provisioning.domain.policy.ExecutionMode.PRODUCTION_AUTO_APPLY)
        candidate.validate()

        if (current?.status == PlanStatus.GENERATED) current.reject()
        if (current?.status == PlanStatus.VALIDATED) current.supersede()
        if (current != null) plans.save(current)
        return plans.save(candidate)
    }
}

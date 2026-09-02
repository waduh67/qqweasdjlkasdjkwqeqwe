package com.duluin.ftth.provisioning.domain.model

import com.duluin.ftth.common.domain.error.ValidationException
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID

interface ProvisioningAggregate {
    val id: UUID
}

enum class DeviceKind { OLT, SWITCH, ROUTER, BRAS }

data class DeviceReference(val kind: DeviceKind, val id: UUID)

enum class VlanEncapsulation { SINGLE_TAG, QINQ, TRANSLATION, NATIVE }

enum class IntentStatus { DRAFT, ACTIVE, SUSPENDED, DECOMMISSIONED }

enum class PlanStatus { GENERATED, VALIDATED, REJECTED, SUPERSEDED }

enum class ExecutionStatus {
    QUEUED,
    RUNNING,
    VERIFYING,
    SUCCEEDED,
    ROLLING_BACK,
    ROLLED_BACK,
    FAILED,
    MANUAL_RECONCILIATION,
}

enum class DriftStatus { NONE, BENIGN, CONFLICTING, UNKNOWN }

enum class ProvisionOperation {
    ENSURE_TAGGED_VLAN,
    ENSURE_ACCESS_PORT,
    ENSURE_PPPOE_TERMINATION,
    VERIFY_STATE,
    BLOCK_PPPOE_SESSIONS,
    REMOVE_ACCESS_PORT,
    REMOVE_TAGGED_VLAN,
    REMOVE_PPPOE_TERMINATION,
}

class NormalizedDeviceState private constructor(
    val values: Map<String, Any?>,
) {
    init {
        validate(values)
    }

    override fun equals(other: Any?): Boolean = other is NormalizedDeviceState && values == other.values

    override fun hashCode(): Int = values.hashCode()

    companion object {
        private val forbiddenFragments = setOf("password", "secret", "credential", "token", "rawcli", "command", "script")

        fun of(values: Map<String, Any?>): NormalizedDeviceState = NormalizedDeviceState(values.toMap())

        fun empty(): NormalizedDeviceState = NormalizedDeviceState(emptyMap())

        private fun validate(value: Any?) {
            when (value) {
                is Map<*, *> -> value.forEach { (key, child) ->
                    val normalizedKey = key.toString().lowercase().filter(Char::isLetterOrDigit)
                    if (forbiddenFragments.any(normalizedKey::contains)) {
                        throw ValidationException("SENSITIVE_FIELD: normalized state cannot contain '$key'")
                    }
                    validate(child)
                }
                is Iterable<*> -> value.forEach(::validate)
                is Array<*> -> value.forEach(::validate)
            }
        }
    }
}

object NormalizedStateHash {
    fun sha256(state: NormalizedDeviceState): String = MessageDigest.getInstance("SHA-256")
        .digest(canonical(state.values).toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private fun canonical(value: Any?): String = when (value) {
        null -> "null"
        is Map<*, *> -> value.entries.sortedBy { it.key.toString() }
            .joinToString(prefix = "{", postfix = "}") { string(it.key.toString()) + ":" + canonical(it.value) }
        is Set<*> -> value.map(::canonical).sorted().joinToString(prefix = "[", postfix = "]")
        is Iterable<*> -> value.joinToString(prefix = "[", postfix = "]", transform = ::canonical)
        is Array<*> -> value.joinToString(prefix = "[", postfix = "]", transform = ::canonical)
        is Boolean, is Number -> value.toString()
        else -> string(value.toString())
    }

    private fun string(value: String): String = "${value.toByteArray(StandardCharsets.UTF_8).size}:$value"
}

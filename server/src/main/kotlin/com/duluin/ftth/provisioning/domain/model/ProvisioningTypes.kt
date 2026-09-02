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
    CANCELLED,
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

enum class NormalizedField(val wireName: String) {
    INTERFACES("interfaces"),
    NAME("name"),
    CONFIGURED("configured"),
    VLAN_ID("vlanId"),
    VLANS("vlans"),
    PORT("port"),
    ENABLED("enabled"),
    EXTERNAL("external");

    companion object {
        fun fromWireNameOrNull(value: String): NormalizedField? = entries.firstOrNull { it.wireName == value }
        fun fromWireName(value: String): NormalizedField = fromWireNameOrNull(value)
            ?: throw ValidationException("NORMALIZED_FIELD_UNSUPPORTED: $value")
    }
}

sealed interface NormalizedValue {
    @JvmInline
    value class Identifier private constructor(val value: String) : NormalizedValue {
        companion object {
            private val safeIdentifier = Regex("^[A-Za-z0-9._:/-]{1,160}$")
            private val forbiddenFragments = setOf(
                "password", "secret", "credential", "token", "privatekey", "rawcli", "command", "script", "-----begin",
            )

            fun of(value: String): Identifier {
                if (!safeIdentifier.matches(value) || forbiddenFragments.any(value.lowercase()::contains)) {
                    throw ValidationException("NORMALIZED_TEXT_INVALID")
                }
                return Identifier(value)
            }
        }
    }

    data class Number(val value: Long) : NormalizedValue
    data class Flag(val value: Boolean) : NormalizedValue
    class Sequence private constructor(val values: List<NormalizedValue>) : NormalizedValue {
        override fun equals(other: Any?): Boolean = other is Sequence && values == other.values
        override fun hashCode(): Int = values.hashCode()

        companion object {
            fun of(values: Collection<NormalizedValue>) = Sequence(values.toList())
        }
    }
    class ObjectValue private constructor(val fields: Map<NormalizedField, NormalizedValue>) : NormalizedValue {
        override fun equals(other: Any?): Boolean = other is ObjectValue && fields == other.fields
        override fun hashCode(): Int = fields.hashCode()

        companion object {
            fun of(fields: Map<NormalizedField, NormalizedValue>) = ObjectValue(fields.toMap())
        }
    }

    companion object {
        fun identifier(value: String): NormalizedValue = Identifier.of(value)
        fun number(value: Int): NormalizedValue = Number(value.toLong())
        fun number(value: Long): NormalizedValue = Number(value)
        fun flag(value: Boolean): NormalizedValue = Flag(value)
        fun sequence(vararg values: NormalizedValue): NormalizedValue = Sequence.of(values.toList())
        fun obj(vararg fields: Pair<NormalizedField, NormalizedValue>): NormalizedValue = ObjectValue.of(mapOf(*fields))
    }
}

class NormalizedDeviceState private constructor(
    val values: Map<NormalizedField, NormalizedValue>,
    val legacyPayload: String?,
) {
    init {
        if (legacyPayload == null) validateFields(values)
        if (legacyPayload != null && values.isNotEmpty()) throw ValidationException("NORMALIZED_STATE_MIXED_FORMAT")
    }

    override fun equals(other: Any?): Boolean =
        other is NormalizedDeviceState && values == other.values && legacyPayload == other.legacyPayload
    override fun hashCode(): Int = 31 * values.hashCode() + (legacyPayload?.hashCode() ?: 0)

    fun canonicalForm(): String = legacyPayload?.let {
        "legacy${it.toByteArray(StandardCharsets.UTF_8).size}:$it"
    } ?: NormalizedStateHash.canonical(values)

    companion object {
        fun of(vararg values: Pair<NormalizedField, NormalizedValue>): NormalizedDeviceState =
            NormalizedDeviceState(mapOf(*values), null)

        fun from(values: Map<NormalizedField, NormalizedValue>): NormalizedDeviceState =
            NormalizedDeviceState(values.toMap(), null)

        internal fun rehydrateLegacy(payload: String): NormalizedDeviceState =
            NormalizedDeviceState(emptyMap(), payload)

        fun empty(): NormalizedDeviceState = NormalizedDeviceState(emptyMap(), null)

        private fun validateFields(values: Map<NormalizedField, NormalizedValue>) {
            values.forEach { (field, value) ->
                val valid = when (field) {
                    NormalizedField.INTERFACES -> value is NormalizedValue.Sequence &&
                        value.values.all { it is NormalizedValue.ObjectValue }
                    NormalizedField.NAME, NormalizedField.PORT -> value is NormalizedValue.Identifier
                    NormalizedField.CONFIGURED, NormalizedField.ENABLED, NormalizedField.EXTERNAL -> value is NormalizedValue.Flag
                    NormalizedField.VLAN_ID -> value is NormalizedValue.Number && value.value in 2..4094
                    NormalizedField.VLANS -> value is NormalizedValue.Sequence && value.values.all {
                        it is NormalizedValue.Number && it.value in 2..4094
                    }
                }
                if (!valid) throw ValidationException("NORMALIZED_FIELD_TYPE_INVALID: ${field.wireName}")
                if (value is NormalizedValue.ObjectValue) validateFields(value.fields)
                if (value is NormalizedValue.Sequence) {
                    value.values.filterIsInstance<NormalizedValue.ObjectValue>().forEach { validateFields(it.fields) }
                }
            }
        }
    }
}

object NormalizedStateHash {
    fun sha256(state: NormalizedDeviceState): String = MessageDigest.getInstance("SHA-256")
        .digest(state.canonicalForm().toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    internal fun canonical(values: Map<NormalizedField, NormalizedValue>): String = values.entries
        .sortedBy { it.key.wireName }
        .joinToString(prefix = "{", postfix = "}") { string(it.key.wireName) + canonical(it.value) }

    private fun canonical(value: NormalizedValue): String = when (value) {
        is NormalizedValue.Identifier -> "s${string(value.value)}"
        is NormalizedValue.Number -> "n${value.value}"
        is NormalizedValue.Flag -> if (value.value) "b1" else "b0"
        is NormalizedValue.Sequence -> value.values.joinToString(prefix = "[", postfix = "]") { canonical(it) }
        is NormalizedValue.ObjectValue -> canonical(value.fields)
    }

    private fun string(value: String): String = "${value.toByteArray(StandardCharsets.UTF_8).size}:$value"
}

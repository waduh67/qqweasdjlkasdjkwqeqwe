package com.duluin.ftth.provisioning.domain.model

import com.duluin.ftth.common.domain.error.ValidationException

internal object PlanAttributePolicy {
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
        "safety.managementComplete",
        "safety.managementSourceId",
        "safety.managementSourceType",
        "safety.interfaceRoles",
        "safety.ipAddresses",
        "safety.vrfs",
        "safety.collectorPaths",
        "safety.requiredOobRoutes",
        "safety.changedOobRoutes",
        "safety.availableOobRoutes",
    )
    private val forbiddenValueFragments = setOf(
        "password", "secret", "credential", "token", "privatekey", "rawcli", "command", "script",
        "-----begin", "/interface ", "configure terminal",
    )
    private val canonicalUuid = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")
    private val hash = Regex("^[a-f0-9]{64}$")
    private val identifier = Regex("^[A-Za-z0-9._:/-]{1,160}$")

    fun validate(attributes: Map<String, String>) {
        validateSafeContent(attributes)
        attributes.forEach { (key, value) ->
            if (key !in allowedKeys) throw ValidationException("PLAN_ATTRIBUTE_UNSUPPORTED: $key")
            val valid = when (key) {
                "intentId" -> canonicalUuid.matches(value)
                "vlanId" -> value.matches(Regex("^[0-9]{1,4}$")) && value.toInt() in 2..4094
                ProvisionStep.PRECONDITION_HASH_ATTRIBUTE, ProvisionPlan.PLAN_PRECONDITION_HASH_ATTRIBUTE -> hash.matches(value)
                "interface", "safety.vendor", "safety.model", "safety.firmware", "safety.transport" -> identifier.matches(value)
                "safety.managementComplete" -> value == "true"
                "safety.managementSourceId" -> canonicalUuid.matches(value)
                "safety.managementSourceType" -> value in setOf("TOPOLOGY_OBSERVATION", "DEVICE_OBSERVATION")
                "safety.interfaceRoles", "safety.ipAddresses", "safety.vrfs", "safety.collectorPaths",
                "safety.requiredOobRoutes", "safety.changedOobRoutes", "safety.availableOobRoutes" ->
                    value.isEmpty() || value.split(',').all(identifier::matches)
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

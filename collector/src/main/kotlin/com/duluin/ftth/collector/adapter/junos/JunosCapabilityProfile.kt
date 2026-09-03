package com.duluin.ftth.collector.adapter.junos

enum class JunosPlatformFamily { EX, MX }

enum class JunosOperation(val operationClass: String) {
    TRANSPORT_VLAN("ENSURE_TAGGED_VLAN"),
    PPPOE_TERMINATION("ENSURE_PPPOE_TERMINATION");

    companion object {
        fun from(operationClass: String): JunosOperation? = entries.singleOrNull {
            it.operationClass == operationClass
        }
    }
}

data class JunosDeviceIdentity(
    val model: String,
    val firmware: String,
)

data class JunosCapabilityProfile(
    val identity: JunosDeviceIdentity,
    val family: JunosPlatformFamily,
    val operations: Set<JunosOperation>,
    val requiredNetconfCapabilities: Set<String>,
    val provisional: Boolean,
    val confirmationTimeoutSeconds: Int,
)

object JunosCapabilityProfiles {
    const val CANDIDATE = "urn:ietf:params:netconf:capability:candidate:1.0"
    const val CONFIRMED_COMMIT = "urn:ietf:params:netconf:capability:confirmed-commit:1.1"
    const val VALIDATE = "urn:ietf:params:netconf:capability:validate:1.1"

    private val requiredCapabilities = setOf(CANDIDATE, CONFIRMED_COMMIT, VALIDATE)
    private val profiles = listOf(
        JunosCapabilityProfile(
            identity = JunosDeviceIdentity("EX4300-48P", "21.4R3-S5.4"),
            family = JunosPlatformFamily.EX,
            operations = setOf(JunosOperation.TRANSPORT_VLAN),
            requiredNetconfCapabilities = requiredCapabilities,
            provisional = true,
            confirmationTimeoutSeconds = 120,
        ),
        JunosCapabilityProfile(
            identity = JunosDeviceIdentity("MX204", "23.4R2-S2.1"),
            family = JunosPlatformFamily.MX,
            operations = setOf(JunosOperation.TRANSPORT_VLAN, JunosOperation.PPPOE_TERMINATION),
            requiredNetconfCapabilities = requiredCapabilities,
            provisional = true,
            confirmationTimeoutSeconds = 120,
        ),
    ).associateBy(JunosCapabilityProfile::identity)

    fun find(identity: JunosDeviceIdentity): JunosCapabilityProfile? = profiles[identity]
}

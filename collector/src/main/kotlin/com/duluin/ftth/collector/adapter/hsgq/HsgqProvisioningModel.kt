package com.duluin.ftth.collector.adapter.hsgq

import com.duluin.ftth.contract.OltManagementTransport
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

data class HsgqSubscriberVlanBinding(val vlanId: Int, val subscriberPort: String)

data class HsgqTaggedUplinkMembership(val vlanId: Int, val uplink: String)

data class HsgqDeviceState(
    val model: String,
    val firmware: String,
    val managementVlanId: Int,
    val managementInterface: String,
    val subscriberBindings: Set<HsgqSubscriberVlanBinding>,
    val taggedUplinks: Set<HsgqTaggedUplinkMembership>,
) {
    fun sha256(): String = MessageDigest.getInstance("SHA-256")
        .digest(canonical().toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    fun managedResourceCount(): Int = subscriberBindings.size + taggedUplinks.size

    private fun canonical(): String = buildString {
        appendField(model)
        appendField(firmware)
        append(managementVlanId).append('|')
        appendField(managementInterface)
        subscriberBindings.sortedWith(compareBy(HsgqSubscriberVlanBinding::vlanId, HsgqSubscriberVlanBinding::subscriberPort))
            .forEach { append(it.vlanId).append(':').appendField(it.subscriberPort) }
        append('|')
        taggedUplinks.sortedWith(compareBy(HsgqTaggedUplinkMembership::vlanId, HsgqTaggedUplinkMembership::uplink))
            .forEach { append(it.vlanId).append(':').appendField(it.uplink) }
    }

    private fun StringBuilder.appendField(value: String) {
        append(value.toByteArray(StandardCharsets.UTF_8).size).append(':').append(value).append('|')
    }
}

data class HsgqDesiredVlan(
    val vlanId: Int,
    val subscriberPort: String,
    val taggedUplinks: Set<String>,
) {
    fun matches(state: HsgqDeviceState, operationClass: String): Boolean = when (operationClass) {
        HsgqOperation.ENSURE_TAGGED_VLAN ->
            HsgqSubscriberVlanBinding(vlanId, subscriberPort) in state.subscriberBindings &&
                taggedUplinks.all { HsgqTaggedUplinkMembership(vlanId, it) in state.taggedUplinks }
        HsgqOperation.REMOVE_TAGGED_VLAN ->
            HsgqSubscriberVlanBinding(vlanId, subscriberPort) !in state.subscriberBindings &&
                taggedUplinks.none { HsgqTaggedUplinkMembership(vlanId, it) in state.taggedUplinks }
        HsgqOperation.VERIFY_STATE ->
            HsgqSubscriberVlanBinding(vlanId, subscriberPort) in state.subscriberBindings &&
                taggedUplinks.all { HsgqTaggedUplinkMembership(vlanId, it) in state.taggedUplinks }
        else -> false
    }
}

object HsgqOperation {
    const val ENSURE_TAGGED_VLAN = "ENSURE_TAGGED_VLAN"
    const val REMOVE_TAGGED_VLAN = "REMOVE_TAGGED_VLAN"
    const val VERIFY_STATE = "VERIFY_STATE"
    val supported: Set<String> = setOf(ENSURE_TAGGED_VLAN, REMOVE_TAGGED_VLAN, VERIFY_STATE)
}

data class HsgqFirmwareFingerprint(
    val model: String,
    val firmware: String,
    val transport: OltManagementTransport,
)

data class HsgqCertification(
    val fingerprint: HsgqFirmwareFingerprint,
    val operationClasses: Set<String>,
    val evidenceSha256: String,
) {
    init {
        require(evidenceSha256.matches(Regex("^[a-f0-9]{64}$")))
        require(operationClasses.isNotEmpty())
    }
}

class HsgqCertificationRegistry(certifications: List<HsgqCertification> = emptyList()) {
    private val byFingerprint = certifications.associateBy(HsgqCertification::fingerprint)

    fun find(fingerprint: HsgqFirmwareFingerprint): HsgqCertification? = byFingerprint[fingerprint]
}

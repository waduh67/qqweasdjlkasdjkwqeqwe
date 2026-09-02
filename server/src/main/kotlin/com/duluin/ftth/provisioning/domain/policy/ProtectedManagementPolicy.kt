package com.duluin.ftth.provisioning.domain.policy

import com.duluin.ftth.provisioning.domain.model.VlanRange

data class PolicyActor(
    val platformAdmin: Boolean,
    val permissions: Set<String>,
)

data class ProtectedManagementResources(
    val vlanRanges: List<VlanRange> = emptyList(),
    val managementIpPrefixes: Set<String> = emptySet(),
    val vrfs: Set<String> = emptySet(),
    val managementInterfaceRoles: Set<String> = setOf("MANAGEMENT"),
    val collectorSourcePaths: Set<String> = emptySet(),
    val requiredOutOfBandRoutes: Set<String> = emptySet(),
)

data class ManagementMutation(
    val vlanIds: Set<Int> = emptySet(),
    val interfaceRoles: Set<String> = emptySet(),
    val ipAddresses: Set<String> = emptySet(),
    val vrfOrRoutingInstances: Set<String> = emptySet(),
    val collectorSourcePaths: Set<String> = emptySet(),
    val requiredOutOfBandRoutes: Set<String> = emptySet(),
    val changedOutOfBandRoutes: Set<String> = emptySet(),
    val availableOutOfBandRoutes: Set<String> = emptySet(),
)

class ProtectedManagementPolicy(resources: ProtectedManagementResources) {
    private val vlanRanges = resources.vlanRanges.toList()
    private val managementPrefixes = resources.managementIpPrefixes.map(IpPrefix::parse)
    private val protectedVrfs = resources.vrfs.mapTo(hashSetOf()) { it.uppercase() }
    private val protectedInterfaceRoles = resources.managementInterfaceRoles.mapTo(hashSetOf()) { it.uppercase() }
    private val protectedCollectorPaths = resources.collectorSourcePaths.toSet()
    private val configuredOutOfBandRoutes = resources.requiredOutOfBandRoutes.toSet()

    @Suppress("UNUSED_PARAMETER")
    fun evaluate(mutation: ManagementMutation, actor: PolicyActor): PolicyDecision {
        val mutationAddresses = mutation.ipAddresses.map(IpLiteral::parse)
        if (mutationAddresses.any { it == null }) {
            return PolicyDecision(false, PolicyCode.INVALID_MANAGEMENT_RESOURCE)
        }
        val protected = mutation.vlanIds.any { vlan -> vlanRanges.any { vlan in it.start..it.endInclusive } } ||
            mutation.interfaceRoles.any { it.uppercase() in protectedInterfaceRoles } ||
            mutationAddresses.filterNotNull().any { address -> managementPrefixes.any { it.contains(address) } } ||
            mutation.vrfOrRoutingInstances.any { it.uppercase() in protectedVrfs } ||
            mutation.collectorSourcePaths.any { it in protectedCollectorPaths } ||
            mutation.changedOutOfBandRoutes.any { it in configuredOutOfBandRoutes } ||
            missingRequiredOutOfBandRoute(mutation)

        return if (protected) {
            PolicyDecision(false, PolicyCode.PROTECTED_MANAGEMENT_RESOURCE)
        } else {
            PolicyDecision(true, PolicyCode.MANAGEMENT_RESOURCES_CLEAR)
        }
    }

    private fun missingRequiredOutOfBandRoute(mutation: ManagementMutation): Boolean {
        val required = configuredOutOfBandRoutes + mutation.requiredOutOfBandRoutes
        return !mutation.availableOutOfBandRoutes.containsAll(required)
    }
}

private data class IpPrefix(private val network: ByteArray, private val prefixLength: Int) {
    fun contains(address: ByteArray): Boolean {
        if (address.size != network.size) return false
        val wholeBytes = prefixLength / 8
        val remainingBits = prefixLength % 8
        if (!(0 until wholeBytes).all { network[it] == address[it] }) return false
        if (remainingBits == 0) return true
        val mask = (0xff shl (8 - remainingBits)) and 0xff
        return (network[wholeBytes].toInt() and mask) == (address[wholeBytes].toInt() and mask)
    }

    companion object {
        fun parse(value: String): IpPrefix {
            val parts = value.split('/', limit = 2)
            require(parts.size == 2) { "MANAGEMENT_IP_PREFIX_INVALID" }
            val address = requireNotNull(IpLiteral.parse(parts[0])) { "MANAGEMENT_IP_PREFIX_INVALID" }
            val prefix = parts[1].toIntOrNull()
            require(prefix != null && prefix in 0..address.size * 8) { "MANAGEMENT_IP_PREFIX_INVALID" }
            return IpPrefix(address, prefix)
        }
    }
}

private object IpLiteral {
    private val decimalOctet = Regex("^[0-9]{1,3}$")
    private val hexadecimalGroup = Regex("^[0-9a-fA-F]{1,4}$")

    fun parse(value: String): ByteArray? {
        if (value.isBlank() || value != value.trim()) return null
        return when {
            '.' in value && ':' !in value -> parseIpv4(value)
            ':' in value && '.' !in value -> parseIpv6(value)
            else -> null
        }
    }

    private fun parseIpv4(value: String): ByteArray? {
        val octets = value.split('.')
        if (octets.size != 4 || octets.any { !decimalOctet.matches(it) }) return null
        val numeric = octets.map { it.toInt() }
        if (numeric.any { it !in 0..255 }) return null
        return numeric.map(Int::toByte).toByteArray()
    }

    private fun parseIpv6(value: String): ByteArray? {
        val compression = value.indexOf("::")
        if (compression != value.lastIndexOf("::")) return null
        if (compression < 0 && (value.startsWith(':') || value.endsWith(':'))) return null

        val left = groups(if (compression < 0) value else value.substring(0, compression)) ?: return null
        val right = if (compression < 0) emptyList() else groups(value.substring(compression + 2)) ?: return null
        val compressedGroups = when {
            compression < 0 && left.size == 8 -> left
            compression >= 0 && left.size + right.size < 8 ->
                left + List(8 - left.size - right.size) { "0" } + right
            else -> return null
        }

        return ByteArray(16).also { bytes ->
            compressedGroups.forEachIndexed { index, group ->
                val number = group.toInt(16)
                bytes[index * 2] = (number ushr 8).toByte()
                bytes[index * 2 + 1] = number.toByte()
            }
        }
    }

    private fun groups(value: String): List<String>? {
        if (value.isEmpty()) return emptyList()
        return value.split(':').takeIf { groups -> groups.all(hexadecimalGroup::matches) }
    }
}

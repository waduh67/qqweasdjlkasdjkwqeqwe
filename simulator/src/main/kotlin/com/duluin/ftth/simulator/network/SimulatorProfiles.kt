package com.duluin.ftth.simulator.network

enum class FingerprintOrigin { SIMULATOR, HARDWARE }

enum class SimulatorCapability {
    CONFIG_LOCK,
    CANDIDATE_CONFIG,
    CONFIRMED_COMMIT,
    REST_RESOURCE_IDS,
    STRICT_PROMPTS,
    PERSISTENCE_RECONNECT,
    ACTIVE_SESSION_CONTROL,
}

data class SimulatorFingerprint(
    val vendor: String,
    val model: String,
    val firmware: String,
    val transport: String,
)

data class SimulatorProfile(
    val id: String,
    val fingerprint: SimulatorFingerprint,
    val capabilities: Set<SimulatorCapability>,
    val supportedOperations: Set<String>,
    val origin: FingerprintOrigin = FingerprintOrigin.SIMULATOR,
)

object SimulatorProfiles {
    private val transportOperations = setOf("ENSURE_TAGGED_VLAN", "REMOVE_TAGGED_VLAN", "VERIFY_STATE")
    private val brasOperations = transportOperations + setOf("ENSURE_PPPOE_TERMINATION", "REMOVE_PPPOE_TERMINATION")
    private val oltOperations = transportOperations + setOf("ENSURE_ACCESS_PORT", "REMOVE_ACCESS_PORT")

    val routerOs = profile(
        "routeros-ccr2004-7.20.2",
        "MIKROTIK", "CCR2004-1G-12S+2XS", "7.20.2", "HTTPS_REST",
        setOf(SimulatorCapability.REST_RESOURCE_IDS, SimulatorCapability.PERSISTENCE_RECONNECT, SimulatorCapability.ACTIVE_SESSION_CONTROL),
        brasOperations,
    )
    val iosXe = profile(
        "iosxe-c9300-17.18.1",
        "CISCO", "C9300-24T", "17.18.1", "NETCONF_SSH",
        netconfCapabilities(),
        transportOperations + setOf("ENSURE_ACCESS_PORT", "REMOVE_ACCESS_PORT"),
    )
    val junos = profile(
        "junos-ex4300-21.4r3-s5.4",
        "JUNIPER", "EX4300-48P", "21.4R3-S5.4", "NETCONF_SSH",
        netconfCapabilities(), setOf("ENSURE_TAGGED_VLAN"),
    )
    val hsgq = profile(
        "hsgq-e04i-v1.0.0",
        "HSGQ", "HSGQ-E04I", "V1.0.0", "SSH",
        cliCapabilities(), oltOperations,
    )
    val huawei = profile(
        "huawei-ma5800-r019",
        "HUAWEI", "SmartAX MA5800-X7", "MA5800V100R019C10", "SSH_CLI",
        cliCapabilities(), oltOperations,
    )
    val zte = profile(
        "zte-c320-v2.0.1p3",
        "ZTE", "ZXA10 C320", "V2.0.1P3", "SSH_CLI",
        cliCapabilities(), oltOperations,
    )
    val simulator = profile(
        "ftth-network-simulator-v1",
        "FTTH", "NETWORK-SIMULATOR", "1.0.0", "IN_MEMORY",
        SimulatorCapability.entries.toSet(), brasOperations + oltOperations,
    )

    val all: List<SimulatorProfile> = listOf(routerOs, iosXe, junos, hsgq, huawei, zte, simulator)
    private val exact = all.associateBy { it.fingerprint }

    fun find(vendor: String, model: String, firmware: String, transport: String): SimulatorProfile? =
        exact[SimulatorFingerprint(vendor, model, firmware, transport)]

    private fun profile(
        id: String,
        vendor: String,
        model: String,
        firmware: String,
        transport: String,
        capabilities: Set<SimulatorCapability>,
        operations: Set<String>,
    ) = SimulatorProfile(id, SimulatorFingerprint(vendor, model, firmware, transport), capabilities, operations)

    private fun netconfCapabilities() = setOf(
        SimulatorCapability.CONFIG_LOCK,
        SimulatorCapability.CANDIDATE_CONFIG,
        SimulatorCapability.CONFIRMED_COMMIT,
        SimulatorCapability.PERSISTENCE_RECONNECT,
    )

    private fun cliCapabilities() = setOf(
        SimulatorCapability.CONFIG_LOCK,
        SimulatorCapability.STRICT_PROMPTS,
        SimulatorCapability.PERSISTENCE_RECONNECT,
    )
}

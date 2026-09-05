package com.duluin.ftth.collector.adapter.zte

interface ZteCliProfile {
    val key: ZteProfileKey
    val certification: ZteCertification
    val capabilities: Set<String>
    fun readCommands(plan: ZteServicePlan): List<String>
    fun applyCommands(plan: ZteServicePlan, mutations: ZteMutationSet): List<String>
    fun inverseCommands(plan: ZteServicePlan, mutations: ZteMutationSet): List<String>
}

class ZteProfileRegistry private constructor(profiles: List<ZteCliProfile>) {
    private val exact = profiles.associateBy(ZteCliProfile::key)

    fun require(key: ZteProfileKey): ZteCliProfile = exact[key]
        ?: throw ZteAdapterException(
            ZteFailureCode.UNRECOGNIZED_DEVICE_RESPONSE,
            "No provisional ZTE profile for exact family and firmware",
        )

    companion object {
        fun provisional(): ZteProfileRegistry = ZteProfileRegistry(
            listOf(
                ZteV201P3Profile("ZXA10 C300"),
                ZteV201P3Profile("ZXA10 C320"),
            ),
        )
    }
}

private class ZteV201P3Profile(family: String) : ZteCliProfile {
    override val key = ZteProfileKey(family, "V2.0.1P3")
    override val certification = ZteCertification.PROVISIONAL
    override val capabilities = setOf(
        "SINGLE_TAG_802_1Q",
        "TAGGED_UPLINK",
        "GPON_TCONT_GEM_ASSOCIATION",
        "GPON_SERVICE_PORT",
        "PERSISTENT_WRITE",
        "READBACK",
        "INVERSE_COMMANDS",
        "CERTIFICATION_PROVISIONAL",
    )

    override fun readCommands(plan: ZteServicePlan): List<String> = listOf(
        "show vlan ${plan.vlanId}",
        "show vlan port ${plan.vlanId}",
        "show running-config interface ${plan.onu.notation}",
    )

    override fun applyCommands(plan: ZteServicePlan, mutations: ZteMutationSet): List<String> = buildList {
        if (!mutations.any) return@buildList
        add("configure terminal")
        if (mutations.vlan) {
            add("vlan ${plan.vlanId}")
            add("exit")
        }
        if (mutations.uplink) {
            add("interface ${plan.uplink.notation}")
            add("switchport vlan ${plan.vlanId} tag")
            add("exit")
        }
        if (mutations.tcont || mutations.gem || mutations.service) {
            add("interface ${plan.onu.notation}")
            if (mutations.tcont) add("tcont ${plan.tcontId} profile ${plan.tcontProfile}")
            if (mutations.gem) add("gemport ${plan.gemPortId} tcont ${plan.tcontId}")
            if (mutations.service) {
                add("service-port ${plan.servicePortId} vport ${plan.gemPortId} user-vlan ${plan.userVlanId} vlan ${plan.vlanId}")
            }
            add("exit")
        }
        add("end")
        add("write")
    }

    override fun inverseCommands(plan: ZteServicePlan, mutations: ZteMutationSet): List<String> = buildList {
        if (!mutations.any) return@buildList
        add("configure terminal")
        if (mutations.service || mutations.gem || mutations.tcont) {
            add("interface ${plan.onu.notation}")
            if (mutations.service) add("no service-port ${plan.servicePortId}")
            if (mutations.gem) add("no gemport ${plan.gemPortId}")
            if (mutations.tcont) add("no tcont ${plan.tcontId}")
            add("exit")
        }
        if (mutations.uplink) {
            add("interface ${plan.uplink.notation}")
            add("no switchport vlan ${plan.vlanId}")
            add("exit")
        }
        if (mutations.vlan) add("no vlan ${plan.vlanId}")
        add("end")
        add("write")
    }
}

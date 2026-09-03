package com.duluin.ftth.collector.adapter.huawei

interface HuaweiCliProfile {
    val key: HuaweiProfileKey
    val certification: HuaweiCertification
    val capabilities: Set<String>
    fun readCommands(plan: HuaweiServicePlan): List<String>
    fun applyCommands(plan: HuaweiServicePlan, mutations: HuaweiMutationSet): List<String>
    fun inverseCommands(plan: HuaweiServicePlan, mutations: HuaweiMutationSet): List<String>
}

class HuaweiProfileRegistry private constructor(profiles: List<HuaweiCliProfile>) {
    private val exact = profiles.associateBy(HuaweiCliProfile::key)

    fun require(key: HuaweiProfileKey): HuaweiCliProfile = exact[key]
        ?: throw HuaweiAdapterException(
            HuaweiFailureCode.UNKNOWN_PROFILE,
            "No provisional Huawei profile exists for the exact family and firmware",
        )

    companion object {
        fun provisional(): HuaweiProfileRegistry = HuaweiProfileRegistry(
            listOf(HuaweiMa5800R019Profile("SmartAX MA5800-X7")),
        )
    }
}

private class HuaweiMa5800R019Profile(family: String) : HuaweiCliProfile {
    override val key = HuaweiProfileKey(family, "MA5800V100R019C10")
    override val certification = HuaweiCertification.PROVISIONAL
    override val capabilities = setOf(
        "SINGLE_TAG_802_1Q",
        "TAGGED_UPLINK",
        "GPON_TCONT_GEM_ASSOCIATION",
        "GPON_SERVICE_PORT",
        "PERSISTENT_SAVE",
        "READBACK",
        "INVERSE_COMMANDS",
        "CERTIFICATION_PROVISIONAL",
    )

    override fun readCommands(plan: HuaweiServicePlan): List<String> = listOf(
        "display vlan ${plan.vlanId}",
        "display port vlan ${plan.vlanId}",
        "display ont-lineprofile gpon profile-id ${plan.lineProfileId}",
        "display service-port ${plan.servicePortId}",
    )

    override fun applyCommands(plan: HuaweiServicePlan, mutations: HuaweiMutationSet): List<String> = buildList {
        if (!mutations.any) return@buildList
        add("config")
        if (mutations.vlan) add("vlan ${plan.vlanId} smart")
        if (mutations.uplink) add("port vlan ${plan.vlanId} ${plan.uplink.frame}/${plan.uplink.slot} ${plan.uplink.port}")
        if (mutations.tcont || mutations.gem || mutations.gemMapping) {
            add("ont-lineprofile gpon profile-id ${plan.lineProfileId}")
            if (mutations.tcont) add("tcont ${plan.tcontId} dba-profile-id ${plan.dbaProfileId}")
            if (mutations.gem) add("gem add ${plan.gemPortId} eth tcont ${plan.tcontId}")
            if (mutations.gemMapping) add("gem mapping ${plan.gemPortId} 0 vlan ${plan.vlanId}")
            add("commit")
            add("quit")
        }
        if (mutations.servicePort) {
            add(
                "service-port ${plan.servicePortId} vlan ${plan.vlanId} gpon ${plan.gpon.notation} " +
                    "ont ${plan.ontId} gemport ${plan.gemPortId} multi-service user-vlan ${plan.userVlanId} " +
                    "tag-transform transparent",
            )
        }
        add("save")
    }

    override fun inverseCommands(plan: HuaweiServicePlan, mutations: HuaweiMutationSet): List<String> = buildList {
        if (!mutations.any) return@buildList
        add("config")
        if (mutations.servicePort) add("undo service-port ${plan.servicePortId}")
        if (mutations.gemMapping || mutations.gem || mutations.tcont) {
            add("ont-lineprofile gpon profile-id ${plan.lineProfileId}")
            if (mutations.gemMapping) add("undo gem mapping ${plan.gemPortId} 0")
            if (mutations.gem) add("undo gem add ${plan.gemPortId}")
            if (mutations.tcont) add("undo tcont ${plan.tcontId}")
            add("commit")
            add("quit")
        }
        if (mutations.uplink) add("undo port vlan ${plan.vlanId} ${plan.uplink.frame}/${plan.uplink.slot} ${plan.uplink.port}")
        if (mutations.vlan) add("undo vlan ${plan.vlanId}")
        add("save")
    }
}

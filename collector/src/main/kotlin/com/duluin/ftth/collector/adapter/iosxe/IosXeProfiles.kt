package com.duluin.ftth.collector.adapter.iosxe

sealed interface IosXeProfile {
    val model: String
    val softwareVersion: String
    val requiredModules: Set<String>
    val supportedOperations: Set<String>
    fun renderEdit(desired: IosXeDesiredConfiguration): String
}

object IosXeProfiles {
    val CATALYST_9300_17_18: IosXeProfile = Catalyst9300Profile
    val ASR_1001_X_17_18: IosXeProfile = Asr1001XProfile

    private val exactProfiles = listOf(CATALYST_9300_17_18, ASR_1001_X_17_18)

    fun resolve(model: String, softwareVersion: String): IosXeProfile? = exactProfiles.singleOrNull {
        it.model == model && it.softwareVersion == softwareVersion
    }
}

private object Catalyst9300Profile : IosXeProfile {
    override val model = "C9300-24T"
    override val softwareVersion = "17.18.1"
    override val requiredModules = setOf(
        "Cisco-IOS-XE-native",
        "Cisco-IOS-XE-vlan",
        "Cisco-IOS-XE-interfaces-oper",
        "Cisco-IOS-XE-acl",
    )
    override val supportedOperations = setOf(
        "ENSURE_TAGGED_VLAN",
        "ENSURE_ACCESS_PORT",
        "REMOVE_ACCESS_PORT",
        "REMOVE_TAGGED_VLAN",
        "VERIFY_STATE",
    )

    override fun renderEdit(desired: IosXeDesiredConfiguration): String {
        val operation = if (desired.remove) "delete" else "merge"
        val vlan = desired.vlanId
        val interfaces = (desired.trunkInterfaces + desired.accessInterfaces).sorted().joinToString("") { name ->
            "<GigabitEthernet><name>${IosXeXml.escape(name.removePrefix("GigabitEthernet"))}</name>" +
                "<switchport><trunk><allowed><vlan><vlans>$vlan</vlans></vlan></allowed></trunk></switchport></GigabitEthernet>"
        }
        val acl = desired.aclName?.let { "<ip><access-list><extended><name>${IosXeXml.escape(it)}</name></extended></access-list></ip>" }.orEmpty()
        return """<config xmlns="urn:ietf:params:xml:ns:netconf:base:1.0"><native xmlns="http://cisco.com/ns/yang/Cisco-IOS-XE-native"><vlan><vlan-list xmlns="http://cisco.com/ns/yang/Cisco-IOS-XE-vlan" operation="$operation"><id>$vlan</id></vlan-list></vlan><interface>$interfaces</interface>$acl</native></config>"""
    }
}

private object Asr1001XProfile : IosXeProfile {
    override val model = "ASR1001-X"
    override val softwareVersion = "17.18.1"
    override val requiredModules = setOf(
        "Cisco-IOS-XE-native",
        "Cisco-IOS-XE-interfaces-oper",
        "Cisco-IOS-XE-acl",
    )
    override val supportedOperations = setOf("ENSURE_TAGGED_VLAN", "REMOVE_TAGGED_VLAN", "VERIFY_STATE")

    override fun renderEdit(desired: IosXeDesiredConfiguration): String {
        require(desired.accessInterfaces.isEmpty()) { "ASR_ACCESS_PORT_UNSUPPORTED" }
        val operation = if (desired.remove) "delete" else "merge"
        val vlan = desired.vlanId
        val interfaces = desired.trunkInterfaces.sorted().joinToString("") { rawName ->
            val name = IosXeXml.escape(rawName.removePrefix("GigabitEthernet"))
            "<GigabitEthernet operation=\"$operation\"><name>$name.$vlan</name><encapsulation><dot1Q><vlan-id>$vlan</vlan-id></dot1Q></encapsulation></GigabitEthernet>"
        }
        val acl = desired.aclName?.let { "<ip><access-list><extended><name>${IosXeXml.escape(it)}</name></extended></access-list></ip>" }.orEmpty()
        return """<config xmlns="urn:ietf:params:xml:ns:netconf:base:1.0"><native xmlns="http://cisco.com/ns/yang/Cisco-IOS-XE-native"><interface>$interfaces</interface>$acl</native></config>"""
    }
}

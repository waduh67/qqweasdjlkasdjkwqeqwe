package com.duluin.ftth.collector.adapter.junos

import com.duluin.ftth.contract.ProvisioningPayload

object JunosConfiguration {
    fun build(operation: JunosOperation, payload: ProvisioningPayload): JunosCandidateChange = when (operation) {
        JunosOperation.TRANSPORT_VLAN -> transportVlan(payload)
        JunosOperation.PPPOE_TERMINATION -> pppoeTermination(payload)
    }

    private fun transportVlan(payload: ProvisioningPayload): JunosCandidateChange {
        val values = payload.values
        val vlanId = required(values.vlanId, "vlanId").toIntOrNull()
            ?.takeIf { it in 1..4094 }
            ?: throw JunosConfigurationException("vlanId must be between 1 and 4094")
        val vlanName = "ftth-$vlanId"
        val irb = required(values.vlanInterface, "vlanInterface")
        val filter = required(values.firewallChain, "firewallChain")
        val trunkPorts = ports(values.trunkPorts)
        val accessPorts = ports(values.accessPorts)
        if (trunkPorts.isEmpty() && accessPorts.isEmpty()) {
            throw JunosConfigurationException("At least one trunk or access port is required")
        }
        val interfaceXml = buildString {
            trunkPorts.distinct().sorted().forEach { port ->
                append("<interface><name>${xml(port)}</name><unit><name>0</name><family><ethernet-switching>")
                append("<interface-mode>trunk</interface-mode><vlan><members>${xml(vlanName)}</members></vlan>")
                append("</ethernet-switching></family></unit></interface>")
            }
            accessPorts.distinct().sorted().forEach { port ->
                append("<interface><name>${xml(port)}</name><unit><name>0</name><family><ethernet-switching>")
                append("<interface-mode>access</interface-mode><vlan><members>${xml(vlanName)}</members></vlan>")
                append("</ethernet-switching></family></unit></interface>")
            }
            append("<interface><name>irb</name><unit><name>$vlanId</name></unit></interface>")
        }
        val configuration = """
            <configuration>
              <vlans><vlan><name>$vlanName</name><vlan-id>$vlanId</vlan-id><l3-interface>${xml(irb)}</l3-interface></vlan></vlans>
              <interfaces>$interfaceXml</interfaces>
              <firewall><family><ethernet-switching><filter><name>${xml(filter)}</name><term><name>allow-service</name><then><accept/></then></term></filter></ethernet-switching></family></firewall>
            </configuration>
        """.trimIndent()
        val expected = buildSet {
            add("vlan:$vlanName:$vlanId")
            add("irb:$irb")
            add("filter:$filter")
            trunkPorts.forEach { add("trunk:$it:$vlanId") }
            accessPorts.forEach { add("access:$it:$vlanId") }
        }
        return JunosCandidateChange(JunosOperation.TRANSPORT_VLAN, JunosEditOperation.MERGE, configuration, expected)
    }

    private fun pppoeTermination(payload: ProvisioningPayload): JunosCandidateChange {
        val values = payload.values
        val vlanId = required(values.vlanId, "vlanId").toIntOrNull()
            ?.takeIf { it in 1..4094 }
            ?: throw JunosConfigurationException("vlanId must be between 1 and 4094")
        val interfaceName = required(values.pppoeInterface, "pppoeInterface")
        val serviceName = required(values.pppoeServiceName, "pppoeServiceName")
        val poolName = required(values.poolName, "poolName")
        val poolRanges = required(values.poolRanges, "poolRanges")
        val profileName = "ftth-pppoe-$vlanId"
        val configuration = """
            <configuration>
              <dynamic-profiles><profile><name>$profileName</name><interfaces><pp0><unit><name>"&#36;junos-interface-unit"</name><pppoe-options><server/></pppoe-options></unit></pp0></interfaces></profile></dynamic-profiles>
              <interfaces><interface><name>${xml(interfaceName.substringBefore('.'))}</name><unit><name>$vlanId</name><vlan-id>$vlanId</vlan-id><family><pppoe><dynamic-profile>$profileName</dynamic-profile><service-name-table>${xml(serviceName)}</service-name-table></pppoe></family></unit></interface></interfaces>
              <access><address-assignment><pool><name>${xml(poolName)}</name><family><inet><network>${xml(poolRanges)}</network></inet></family></pool></address-assignment><subscribers><profile>$profileName</profile></subscribers></access>
            </configuration>
        """.trimIndent()
        val expected = setOf(
            "pppoe-interface:$interfaceName",
            "pppoe-service:$serviceName",
            "subscriber-profile:$profileName",
            "address-pool:$poolName:$poolRanges",
        )
        return JunosCandidateChange(JunosOperation.PPPOE_TERMINATION, JunosEditOperation.MERGE, configuration, expected)
    }

    private fun required(value: String?, name: String): String = value?.takeIf(String::isNotBlank)
        ?: throw JunosConfigurationException("$name is required")

    private fun ports(value: String?): List<String> = value.orEmpty()
        .split(',')
        .map(String::trim)
        .filter(String::isNotEmpty)

    private fun xml(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")
}

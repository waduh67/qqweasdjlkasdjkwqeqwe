package com.duluin.ftth.collector.adapter.huawei

object HuaweiStateParser {
    fun parse(
        plan: HuaweiServicePlan,
        vlanBody: String,
        uplinkBody: String,
        lineProfileBody: String,
        servicePortBody: String,
    ): HuaweiObservedState = HuaweiObservedState(
        vlanExists = presence(vlanBody, Regex("(?m)^VLAN ID\\s*:\\s*${plan.vlanId}\\s*$"), "VLAN"),
        taggedUplinkMember = presence(
            uplinkBody,
            Regex("(?m)^${Regex.escape(plan.uplink.notation)}\\s+tagged\\s*$"),
            "tagged uplink",
        ),
        onuGemTcont = parseAssociation(plan, lineProfileBody),
        servicePort = parseServicePort(servicePortBody),
    )

    fun parseServicePort(body: String): ServicePortObservation? {
        if (body == HuaweiTranscriptParser.NO_MATCH) return null
        return ServicePortObservation(
            servicePortId = singleInt(body, "Service-port index", "service-port ID"),
            vlanId = singleInt(body, "VLAN ID", "service-port VLAN"),
            gponPort = singleValue(body, Regex("(?m)^F/S/P\\s*:\\s*([0-9]+/[0-9]+/[0-9]+)\\s*$"), "service-port F/S/P"),
            onuId = singleInt(body, "ONT ID", "service-port ONT"),
            gemPortId = singleInt(body, "GEM port index", "service-port GEM"),
            userVlanId = singleInt(body, "User VLAN ID", "service-port user VLAN"),
        )
    }

    private fun parseAssociation(plan: HuaweiServicePlan, body: String): OnuGemTcontObservation? {
        if (body == HuaweiTranscriptParser.NO_MATCH) return null
        val profileId = singleInt(body, "Line profile ID", "line profile ID")
        if (profileId != plan.lineProfileId) ambiguous("line profile identity")
        val tcont = optionalPair(
            body,
            Regex("(?m)^T-CONT\\s+([0-9]+)\\s+DBA Profile-ID\\s+([0-9]+)\\s*$"),
            "T-CONT association",
        )
        val gem = optionalPair(
            body,
            Regex("(?m)^GEM\\s+([0-9]+)\\s+T-CONT\\s+([0-9]+)\\s*$"),
            "GEM association",
        )
        val mapping = optionalTriple(
            body,
            Regex("(?m)^GEM Mapping\\s+([0-9]+)\\s+([0-9]+)\\s+VLAN\\s+([0-9]+)\\s*$"),
            "GEM mapping",
        )
        if (tcont == null && gem == null && mapping == null) return null
        if (tcont == null || gem == null || mapping == null || gem.first != mapping.first || tcont.first != gem.second) {
            ambiguous("incomplete ONU/GEM/T-CONT association")
        }
        return OnuGemTcontObservation(profileId, tcont.first, tcont.second, gem.first, mapping.third)
    }

    private fun presence(body: String, expected: Regex, label: String): Boolean = when {
        body == HuaweiTranscriptParser.NO_MATCH -> false
        expected.containsMatchIn(body) -> true
        else -> ambiguous("$label readback")
    }

    private fun singleInt(body: String, label: String, field: String): Int = singleValue(
        body,
        Regex("(?m)^${Regex.escape(label)}\\s*:\\s*([0-9]+)\\s*$"),
        field,
    ).toInt()

    private fun singleValue(body: String, pattern: Regex, field: String): String {
        val matches = pattern.findAll(body).toList()
        if (matches.size != 1) ambiguous(field)
        return matches.single().groupValues[1]
    }

    private fun optionalPair(body: String, pattern: Regex, field: String): Pair<Int, Int>? {
        val values = optionalValues(body, pattern, field) ?: return null
        return values[0].toInt() to values[1].toInt()
    }

    private fun optionalTriple(body: String, pattern: Regex, field: String): Triple<Int, Int, Int>? {
        val values = optionalValues(body, pattern, field) ?: return null
        return Triple(values[0].toInt(), values[1].toInt(), values[2].toInt())
    }

    private fun optionalValues(body: String, pattern: Regex, field: String): List<String>? {
        val matches = pattern.findAll(body).toList()
        if (matches.size > 1) ambiguous(field)
        return matches.singleOrNull()?.groupValues?.drop(1)
    }

    private fun ambiguous(field: String): Nothing = throw HuaweiAdapterException(
        HuaweiFailureCode.AMBIGUOUS_READBACK,
        "Huawei $field was missing, conflicting, or ambiguous",
    )
}

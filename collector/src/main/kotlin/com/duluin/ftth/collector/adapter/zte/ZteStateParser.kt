package com.duluin.ftth.collector.adapter.zte

internal object ZteStateParser {
    fun parse(plan: ZteServicePlan, vlanBody: String, uplinkBody: String, onuBody: String): ZteNormalizedState {
        val vlanPresent = presence(
            vlanBody,
            Regex("(?m)^VLAN ID\\s*:\\s*${plan.vlanId}\\s*$"),
            "VLAN readback",
        )
        val uplinkTagged = presence(
            uplinkBody,
            Regex("(?m)^${Regex.escape(plan.uplink.notation)}\\s+tagged\\s*$"),
            "uplink VLAN readback",
        )
        if (!Regex("(?m)^interface ${Regex.escape(plan.onu.notation)}\\s*$").containsMatchIn(onuBody)) {
            throw ZteAdapterException(
                ZteFailureCode.UNRECOGNIZED_DEVICE_RESPONSE,
                "ONU running configuration did not identify the requested interface",
            )
        }
        return ZteNormalizedState(
            vlanPresent = vlanPresent,
            uplinkTagged = uplinkTagged,
            tcontProfile = parseSingle(
                onuBody,
                Regex("(?m)^\\s*tcont ${plan.tcontId} profile ([A-Za-z0-9._-]+)\\s*$"),
                "T-CONT",
            )?.groupValues?.get(1),
            gemTcontId = parseSingle(
                onuBody,
                Regex("(?m)^\\s*gemport ${plan.gemPortId} tcont ([1-9][0-9]*)\\s*$"),
                "GEM port",
            )?.groupValues?.get(1)?.toInt(),
            serviceBinding = parseSingle(
                onuBody,
                Regex(
                    "(?m)^\\s*service-port ${plan.servicePortId} vport ([1-9][0-9]*) " +
                        "user-vlan ([1-9][0-9]*) vlan ([1-9][0-9]*)\\s*$",
                ),
                "service port",
            )?.let { match ->
                ZteServiceBinding(
                    vport = match.groupValues[1].toInt(),
                    userVlanId = match.groupValues[2].toInt(),
                    vlanId = match.groupValues[3].toInt(),
                )
            },
        )
    }

    private fun presence(body: String, expected: Regex, label: String): Boolean = when {
        body == ZteTranscriptParser.NO_MATCH -> false
        expected.containsMatchIn(body) -> true
        else -> throw ZteAdapterException(
            ZteFailureCode.UNRECOGNIZED_DEVICE_RESPONSE,
            "$label was neither an exact match nor an explicit absence",
        )
    }

    private fun parseSingle(body: String, pattern: Regex, label: String): MatchResult? {
        val matches = pattern.findAll(body).toList()
        if (matches.size > 1) {
            throw ZteAdapterException(ZteFailureCode.UNRECOGNIZED_DEVICE_RESPONSE, "Ambiguous $label readback")
        }
        return matches.singleOrNull()
    }
}

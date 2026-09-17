package com.duluin.ftth.snmp

import com.duluin.ftth.contract.OnuDownCause
import com.duluin.ftth.contract.OnuOperationalStatus

/** Nullable OIDs/scales are unavailable, not a request to infer a vendor default. */
data class MibProfile(
    val vendor: String,
    val serialNumberOid: String?,
    val statusOid: String?,
    val rxPowerOid: String?,
    val txPowerOid: String?,
    val distanceOid: String?,
    val uptimeOid: String?,
    val opticalPowerDivisor: Double?,
    val opticalPowerSentinels: Set<Long>,
    val statusMapping: Map<String, OnuOperationalStatus>,
    val downCauseOid: String? = null,
    val downCauseMapping: Map<String, OnuDownCause> = emptyMap(),
    val lastOffAtOid: String? = null,
    val lastOnAtOid: String? = null,
    val serialIsHex: Boolean = true,
)

/** Evidence and limitations: docs/gpon-profile-evidence.md. No GPON profile is hardware-validated. */
object MibProfiles {
    /** Legacy compatibility assumptions; no matching vendor-authored GPON MIB was established. */
    val ZTE = MibProfile(
        vendor = "ZTE",
        serialNumberOid = "1.3.6.1.4.1.3902.1012.3.28.1.1.5",
        statusOid = "1.3.6.1.4.1.3902.1012.3.28.2.1.4",
        rxPowerOid = "1.3.6.1.4.1.3902.1012.3.50.12.1.1.10",
        txPowerOid = "1.3.6.1.4.1.3902.1012.3.50.12.1.1.14",
        distanceOid = "1.3.6.1.4.1.3902.1012.3.11.3.1.6",
        uptimeOid = null,
        opticalPowerDivisor = 1_000.0,
        opticalPowerSentinels = setOf(2_147_483_647L, -2_147_483_648L),
        statusMapping = mapOf("1" to OnuOperationalStatus.OFFLINE, "2" to OnuOperationalStatus.LOS, "3" to OnuOperationalStatus.ONLINE),
    )

    /** Exact HUAWEI-XPON object definitions from public MIB mirrors, not model/firmware certification. */
    val HUAWEI = MibProfile(
        vendor = "HUAWEI",
        serialNumberOid = "1.3.6.1.4.1.2011.6.128.1.1.2.43.1.3",
        statusOid = "1.3.6.1.4.1.2011.6.128.1.1.2.46.1.15",
        rxPowerOid = "1.3.6.1.4.1.2011.6.128.1.1.2.51.1.4",
        txPowerOid = "1.3.6.1.4.1.2011.6.128.1.1.2.51.1.3",
        distanceOid = "1.3.6.1.4.1.2011.6.128.1.1.2.46.1.20",
        uptimeOid = null,
        opticalPowerDivisor = 100.0,
        opticalPowerSentinels = setOf(2_147_483_647L, 65_535L),
        statusMapping = mapOf("1" to OnuOperationalStatus.ONLINE, "2" to OnuOperationalStatus.OFFLINE, "-1" to OnuOperationalStatus.UNKNOWN),
    )

    /** Previous identity/status OIDs describe a PON description/speed; no safe GPON replacements are documented. */
    val FIBERHOME = MibProfile(
        vendor = "FIBERHOME",
        serialNumberOid = null,
        statusOid = null,
        rxPowerOid = null,
        txPowerOid = null,
        distanceOid = null,
        uptimeOid = null,
        opticalPowerDivisor = null,
        opticalPowerSentinels = emptySet(),
        statusMapping = emptyMap(),
    )

    fun all(): List<MibProfile> = listOf(ZTE, HUAWEI, FIBERHOME)
}

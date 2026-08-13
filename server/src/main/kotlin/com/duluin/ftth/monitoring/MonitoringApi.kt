package com.duluin.ftth.monitoring

import java.time.Instant
import java.util.UUID

/**
 * Kontrak publik module monitoring untuk module lain (gis, saat mewarnai peta
 * menurut kondisi hidup jaringan).
 */
interface MonitoringApi {

    /**
     * Entitas yang sedang punya alarm terbuka. Dipakai gis untuk menyorot kabel
     * yang hilir-nya bermasalah — "perangkat modar → kabel merah".
     */
    fun activeImpacts(): List<AlarmImpact>

    /**
     * Bacaan hidup terakhir tiap ONU dalam himpunan (status, Rx, jarak, sebab
     * putus). Dipakai gis untuk memperkaya daftar "tetangga pelanggan" dengan
     * kondisi nyata tiap sambungan. Telemetri optik tetap milik monitoring; module
     * lain hanya membacanya lewat kontrak ini. ONU tanpa bacaan tidak muncul di peta.
     */
    fun latestMetricsByOnuIds(onuIds: Set<UUID>): Map<UUID, OnuLiveMetric>
}

/** Bacaan hidup terakhir satu ONU, cukup untuk menandai kondisi di daftar tetangga. */
data class OnuLiveMetric(
    val onuId: UUID,
    /** ONLINE, OFFLINE, atau LOS. */
    val status: String,
    val rxPowerDbm: Double?,
    /** Daya pancar ONU (dBm) dari bacaan yang sama; `null` bila OLT tak melaporkannya. */
    val txPowerDbm: Double?,
    val distanceMeters: Int?,
    /** Sebab putus terakhir dari register OLT (mis. DYING_GASP, LOS); `null` bila tak dilaporkan. */
    val downCause: String?,
    val lastOffAt: Instant?,
    val lastOnAt: Instant?,
)

/** Satu entitas terdampak beserta alarm terbuka yang menyebabkannya. */
data class AlarmImpact(
    /** ONU, OLT, ODP, ODC, atau COLLECTOR. */
    val entityType: String,
    val entityId: UUID,
    /** INFO, WARNING, atau CRITICAL. */
    val severity: String,
    /** Jenis alarm, mis. ONU_LOS, OLT_UNREACHABLE — untuk menjelaskan "kenapa". */
    val kind: String,
    /** Label entitas terdampak, mis. "OLT-BKS-01" atau serial ONU + nama pelanggan. */
    val label: String,
    /**
     * Sebab putus terakhir dari register OLT untuk impact ONU (mis. DYING_GASP,
     * LOS) — bahan korelasi insiden membedakan "area mati listrik" dari "fiber
     * putus" saat sekelompok ONU serentak padam. `null` untuk entitas non-ONU atau
     * bila OLT tidak melaporkannya.
     */
    val downCause: String? = null,
)

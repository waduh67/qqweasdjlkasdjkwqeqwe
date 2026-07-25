package com.duluin.ftth.monitoring

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
}

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

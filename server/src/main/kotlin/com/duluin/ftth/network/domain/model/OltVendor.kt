package com.duluin.ftth.network.domain.model

/**
 * Vendor OLT yang didukung. Menentukan adapter mana yang dipakai collector untuk
 * bicara SNMP/Telnet ke perangkat (Phase 2); [OTHER] tetap bisa diinventarisasi
 * meski belum bisa dimonitor otomatis.
 */
enum class OltVendor {
    ZTE,
    HUAWEI,
    FIBERHOME,
    NOKIA,

    /** OLT EPON berbasis chipset HSGQ (mis. HSGQ-E04I) — jamak di ISP kecil, identitas ONU = MAC. */
    HSGQ,
    OTHER,
    ;

    fun monitoringSupported(): Boolean = this != OTHER
}

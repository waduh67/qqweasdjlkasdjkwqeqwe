package com.duluin.ftth.simulator.olt

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Setelan armada OLT tiruan. Satu simulator bisa menyalakan BEBERAPA OLT sekaligus —
 * tiap [OltInstance] menjadi satu agen SNMP di port UDP-nya sendiri. Semua berbagi
 * [bindAddress] (satu kontainer = satu IP), jadi di app tiap OLT didaftarkan dengan
 * `management_ip` yang SAMA tapi `snmp_port` berbeda.
 *
 * Nilai baku meniru HSGQ-E04I (EPON) karena OID-nya sudah diverifikasi lapangan di
 * [com.duluin.ftth.snmp.HsgqEponSnmpAdapter] — profil paling tepercaya untuk ditiru.
 */
@ConfigurationProperties(prefix = "ftth.sim.olt")
data class OltSimProperties(
    val enabled: Boolean = true,
    /** Alamat bind bersama semua agen (0.0.0.0 = seluruh antarmuka kontainer). */
    val bindAddress: String = "0.0.0.0",
    /** Daftar OLT yang ditiru. Kosong/absen → satu OLT baku (kompat mundur). */
    val instances: List<OltInstance> = listOf(OltInstance()),
)

/**
 * Satu OLT tiruan.
 *
 * Daftarkan di app dengan vendor `HSGQ`, `management_ip` = alamat kontainer simulator,
 * `snmp_port` = [port], `snmp_community` = [community]; poller server menemukan
 * [ponCount] × [onusPerPon] ONU di sini.
 */
data class OltInstance(
    /** Port UDP SNMP. Tiap OLT WAJIB port berbeda (satu IP, banyak agen). */
    val port: Int = 161,
    val community: String = "public",
    val sysDescr: String = "HSGQ-E04I EPON OLT (ftth lab simulator)",
    val ponCount: Int = 2,
    val onusPerPon: Int = 8,
    /**
     * Pembeda serial antar-OLT: menjadi oktet ke-4 MAC (`C0:FD:84:<macSlot>:pon:onu`).
     * WAJIB unik per OLT (0..255) — sebab serial = identitas ONU di app; dua OLT dengan
     * pon/onu sama tapi macSlot sama menghasilkan serial KEMBAR dan app mengira satu ONU.
     */
    val macSlot: Int = 0,
)

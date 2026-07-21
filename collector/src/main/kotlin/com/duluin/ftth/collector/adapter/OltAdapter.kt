package com.duluin.ftth.collector.adapter

import com.duluin.ftth.contract.OltTarget
import com.duluin.ftth.contract.OnuReading

/**
 * Kontrak untuk berbicara dengan satu jenis OLT.
 *
 * Inilah titik di mana perbedaan vendor diisolasi. Setiap vendor memakai MIB,
 * OID, dan satuan yang berbeda — ZTE melaporkan redaman dalam 0,001 dBm sementara
 * Huawei dalam 0,01 dBm — dan semua keanehan itu berhenti di sini. Bagian lain
 * collector maupun server hanya pernah melihat [OnuReading] yang sudah seragam.
 *
 * Menambah vendor baru berarti menambah satu implementasi, bukan menyentuh
 * loop polling.
 */
interface OltAdapter {

    /** Nama vendor sebagaimana dikenal server, mis. `ZTE`. */
    val vendor: String

    /**
     * Memastikan perangkat bisa dihubungi sebelum polling penuh dijalankan.
     * Dipisah agar OLT yang mati bisa dilaporkan cepat tanpa menunggu seluruh
     * walk SNMP kehabisan waktu.
     */
    fun probe(target: OltTarget): ProbeResult

    /** Membaca seluruh ONU di bawah OLT ini beserta metrik optiknya. */
    fun pollOnus(target: OltTarget): List<OnuReading>
}

sealed interface ProbeResult {
    data class Reachable(val systemDescription: String?, val roundTripMillis: Long) : ProbeResult
    data class Unreachable(val reason: String) : ProbeResult
}

/**
 * Dilempar adapter ketika perangkat menjawab tapi jawabannya tidak masuk akal —
 * dibedakan dari perangkat yang tidak bisa dihubungi, karena penanganannya beda:
 * yang satu masalah jaringan, yang satu masalah kecocokan firmware/MIB.
 */
class OltProtocolException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * Memilih adapter sesuai vendor OLT.
 *
 * OLT dengan vendor yang tidak dikenal sengaja menghasilkan `null`, bukan
 * exception: perangkat semacam itu tetap boleh ada di inventory (didata,
 * dipetakan) hanya saja belum bisa dimonitor otomatis.
 */
class AdapterRegistry(adapters: List<OltAdapter>) {

    private val byVendor = adapters.associateBy { it.vendor.uppercase() }

    fun forVendor(vendor: String): OltAdapter? = byVendor[vendor.uppercase()]

    val supportedVendors: Set<String> get() = byVendor.keys
}

package com.duluin.ftth.snmp

/**
 * Abstraksi minimal atas hal yang dibutuhkan adapter GPON dari SNMP: membaca satu
 * skalar dan meng-walk beberapa kolom tabel.
 *
 * Dipisahkan sebagai antarmuka agar logika penafsiran ([GponSnmpAdapter]) bisa
 * diuji dengan baris SNMP tiruan — perbedaan antar-vendor (skala redaman, format
 * serial, sentinel "tidak terbaca") justru bagian yang paling rawan salah dan
 * paling perlu diuji, sementara transport UDP-nya paling tidak menarik. Dengan
 * seam ini, seluruh keanehan MIB teruji sekarang; verifikasi ke perangkat
 * sungguhan tinggal memastikan OID-nya benar (Phase 2b).
 */
interface SnmpReader : AutoCloseable {

    fun get(oid: String): String?

    fun walkTable(columnOids: List<String>): Map<String, Map<String, String>>
}

/**
 * Membuka sesi SNMP ke sebuah perangkat. Implementasi baku memakai [SnmpSession]
 * di atas UDP; pengujian menyuplai implementasi yang mengembalikan baris tiruan.
 */
fun interface SnmpReaderFactory {
    fun open(host: String, port: Int, community: String): SnmpReader
}

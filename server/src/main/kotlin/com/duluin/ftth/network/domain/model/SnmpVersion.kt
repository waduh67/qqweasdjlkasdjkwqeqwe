package com.duluin.ftth.network.domain.model

/**
 * Versi protokol SNMP yang dipakai collector saat mem-polling OLT.
 *
 * v1/v2c memakai community string (jalur yang sudah didukung); v3 menambah
 * autentikasi & privasi — disimpan sebagai preferensi perangkat, poller memakainya
 * saat membuka sesi SNMP.
 */
enum class SnmpVersion { V1, V2C, V3 }

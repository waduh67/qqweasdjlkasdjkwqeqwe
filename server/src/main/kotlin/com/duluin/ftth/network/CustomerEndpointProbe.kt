package com.duluin.ftth.network

import java.util.UUID

/**
 * Menerjemahkan ujung kabel drop yang berupa RUMAH menjadi nama orangnya.
 *
 * Module network menyimpan ujung itu apa adanya — jenis CUSTOMER dengan sebuah
 * id — dan memang tak boleh tahu lebih dari itu: customer sudah bergantung pada
 * network, jadi arah sebaliknya akan jadi siklus. Sama seperti [OdpUsageProbe],
 * kontraknya dideklarasikan di sini dan diisi module yang memiliki datanya.
 *
 * Bedanya dengan [OdpUsageProbe.occupantsOf] penting dan justru itu sebabnya
 * kontrak ini berdiri sendiri: yang itu bertanya "siapa yang ONU-nya terdaftar
 * di kotak ini", yang ini "siapa yang rumahnya ada di ujung serat ini". Persis
 * di selisih keduanya letak keadaan yang paling perlu ditunjukkan — drop sudah
 * ditarik dan dilas, pelanggannya belum dibukukan sama sekali. Kalau namanya
 * ikut dicari lewat daftar penghuni, orang itu justru muncul tanpa nama pada
 * satu-satunya layar yang bisa menemukannya.
 */
interface CustomerEndpointProbe {

    /** Nama per id; id yang tak dikenal cukup tak muncul di hasil. */
    fun namesOf(customerIds: Set<UUID>): Map<UUID, String>
}

package com.duluin.ftth.incident

import java.util.UUID

/**
 * Kontrak publik module incident untuk module lain (notification saat menyiarkan
 * pemberitahuan gangguan ke pelanggan terdampak sebuah insiden).
 *
 * Sengaja tidak mengekspos agregat `Incident`: pemanggil hanya perlu tahu SIAPA
 * yang terdampak, bukan lifecycle insidennya. Daftar kontak dihitung ulang dari
 * akar masalah lewat network + customer setiap kali dipanggil — insiden sendiri
 * tidak menyimpan id pelanggan terdampak, hanya jumlahnya.
 */
interface IncidentApi {

    /**
     * Pelanggan yang terdampak sebuah insiden, diturunkan dari akar masalahnya:
     * OLT/ODC/ODP → seluruh penghuni ODP di hilirnya; ONU → pelanggan pemiliknya.
     *
     * @throws com.duluin.ftth.common.domain.error.NotFoundException bila insiden tak ada.
     */
    fun affectedContacts(incidentId: UUID): List<AffectedContact>
}

/** Satu pelanggan terdampak insiden — sasaran broadcast. */
data class AffectedContact(
    val customerId: UUID,
    val code: String,
    val name: String,
    val phone: String?,
)

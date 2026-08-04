package com.duluin.ftth.network.domain.model

import java.util.UUID

/**
 * Ujung sebuah kabel: simpul mana, dan port fisik mana pada simpul itu.
 *
 * Menyempurnakan [NetworkNodeRef] yang hanya menyebut "ODC ini" menjadi "kaki
 * splitter ke-3 ODC ini" atau "PON port X OLT ini" — supaya sebuah kabel jelas
 * mencolok dari port mana ke port mana, dan dua kabel tidak diam-diam berebut
 * port keluaran yang sama.
 *
 * Konvensi port per jenis simpul:
 * - OLT      → [ponPortId] (PON port berlabel, entitas tersendiri); [portNumber] null.
 * - ODC      → [portNumber] = kaki splitter 1..kapasitas di sisi keluaran; input tunggal.
 * - ODP      → [portNumber] = slot pelanggan 1..kapasitas di sisi keluaran; input tunggal.
 * - CUSTOMER → terminal, tanpa port.
 *
 * Semua port opsional demi kompatibilitas kabel lama yang direkam sebelum fitur
 * ini ada (kolomnya NULL). Validasi bentuk/penempatan port ada di
 * [CableType.assertEndpoints]; keharusan port pada kabel baru, batas kapasitas,
 * dan okupansi (satu port keluaran satu kabel) ditegakkan di CableService karena
 * butuh repository — persis pola "integritas rujukan divalidasi di domain, bukan DB".
 */
data class NetworkEndpoint(
    val kind: NetworkNodeKind,
    val id: UUID,
    val ponPortId: UUID? = null,
    val portNumber: Int? = null,
) {
    /** Rujukan simpul tanpa info port — untuk pengecekan keberadaan simpul yang sudah ada. */
    val ref: NetworkNodeRef get() = NetworkNodeRef(kind, id)

    companion object {
        fun of(ref: NetworkNodeRef, ponPortId: UUID? = null, portNumber: Int? = null): NetworkEndpoint =
            NetworkEndpoint(ref.kind, ref.id, ponPortId, portNumber)
    }
}

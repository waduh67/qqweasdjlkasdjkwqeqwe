package com.duluin.ftth.network.domain.model

import java.util.UUID

/** Jenis simpul yang bisa menjadi ujung sebuah kabel. */
enum class NetworkNodeKind {
    SITE,
    OLT,
    ODC,
    ODP,
    CUSTOMER,
}

/**
 * Rujukan polimorfik ke sebuah simpul jaringan.
 *
 * Kabel sengaja tidak memakai foreign key ke tabel spesifik: ujungnya bisa berupa
 * ODC, ODP, maupun rumah pelanggan, dan menambah jenis simpul baru nanti tidak
 * perlu mengubah skema. Konsekuensinya integritas rujukan divalidasi di domain,
 * bukan oleh DB — lihat `CableService`.
 */
data class NetworkNodeRef(val kind: NetworkNodeKind, val id: UUID)

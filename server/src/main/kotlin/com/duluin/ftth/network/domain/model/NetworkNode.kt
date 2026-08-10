package com.duluin.ftth.network.domain.model

import java.util.UUID

/** Jenis simpul yang bisa menjadi ujung sebuah kabel. */
enum class NetworkNodeKind {
    SITE,
    OLT,
    ODC,
    ODP,

    /**
     * Kotak sambung. Satu-satunya simpul yang tak punya port keluaran: kabel
     * berhenti di sini bukan karena "dicolok", melainkan karena seratnya
     * disambung ke serat kabel berikutnya.
     */
    JOINT_BOX,
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

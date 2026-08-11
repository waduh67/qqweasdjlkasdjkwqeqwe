package com.duluin.ftth.network.adapter.outbound.persistence

import com.duluin.ftth.common.infrastructure.persistence.TenantAwareJpaEntity
import com.duluin.ftth.network.domain.model.ClosureKind
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Table
import java.util.UUID

@Entity
@Table(name = "splitter")
class SplitterJpaEntity(
    id: UUID,

    /**
     * Pemilik polimorfik (ODC/ODP), karena itu tanpa relasi JPA maupun foreign
     * key — lihat V92. Keutuhannya dijaga service saat kabinetnya dihapus.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "owner_kind", nullable = false, length = 10, updatable = false)
    var ownerKind: ClosureKind,

    @Column(name = "owner_id", nullable = false, updatable = false)
    var ownerId: UUID,

    @Column(nullable = false, length = 40, updatable = false)
    var code: String,

    /** Disimpan sebagai label lapangan ("1:8"), bukan nama konstanta enum. */
    @Column(nullable = false, length = 10)
    var ratio: String,

    @Column(length = 200)
    var note: String?,
) : TenantAwareJpaEntity(id)

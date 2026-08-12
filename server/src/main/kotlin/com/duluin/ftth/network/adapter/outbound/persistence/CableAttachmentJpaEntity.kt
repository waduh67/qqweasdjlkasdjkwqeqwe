package com.duluin.ftth.network.adapter.outbound.persistence

import com.duluin.ftth.common.infrastructure.persistence.TenantAwareJpaEntity
import com.duluin.ftth.network.domain.model.CableAttachmentRole
import com.duluin.ftth.network.domain.model.NetworkNodeKind
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Table
import java.util.UUID

@Entity
@Table(name = "cable_attachment")
class CableAttachmentJpaEntity(
    id: UUID,

    @Column(name = "cable_id", nullable = false, updatable = false)
    var cableId: UUID,

    /** Posisi sepanjang rute: 0 = pangkal, terbesar = ujung. Lihat V99. */
    @Column(name = "sequence", nullable = false)
    var sequence: Int,

    @Enumerated(EnumType.STRING)
    @Column(name = "node_kind", nullable = false, length = 20)
    var nodeKind: NetworkNodeKind,

    @Column(name = "node_id", nullable = false)
    var nodeId: UUID,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var role: CableAttachmentRole,

    /** PON port OLT; hanya berisi pada singgahan berperan END di sisi OLT. */
    @Column(name = "pon_port_id")
    var ponPortId: UUID?,

    /** Slot ODP / kaki ODC (usang); hanya berisi pada singgahan berperan END. */
    @Column(name = "port_number")
    var portNumber: Int?,
) : TenantAwareJpaEntity(id)

package com.duluin.ftth.network.adapter.outbound.persistence

import com.duluin.ftth.common.infrastructure.persistence.TenantAwareJpaEntity
import com.duluin.ftth.network.domain.model.ClosureKind
import com.duluin.ftth.network.domain.model.ConnectionPointKind
import com.duluin.ftth.network.domain.model.SpliceMethod
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Table
import java.util.UUID

@Entity
@Table(name = "fiber_connection")
class FiberConnectionJpaEntity(
    id: UUID,

    @Enumerated(EnumType.STRING)
    @Column(name = "closure_kind", nullable = false, length = 20, updatable = false)
    var closureKind: ClosureKind,

    @Column(name = "closure_id", nullable = false, updatable = false)
    var closureId: UUID,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var method: SpliceMethod,

    @Column(name = "loss_db")
    var lossDb: Double?,

    @Column(length = 200)
    var note: String?,
) : TenantAwareJpaEntity(id)

/**
 * Sisi sambungan. Cuma penanda posisi di dalam satu baris sambungan — kedua sisi
 * setara, A bukan "asal" dan B bukan "tujuan".
 */
enum class ConnectionSide { A, B }

/**
 * Satu ujung sambungan sebagai BARIS sendiri, bukan sepuluh kolom berawalan
 * `a_` dan `b_` di baris induknya. Bentuk inilah yang memungkinkan unique index
 * menegakkan "satu titik dipakai sekali" — aturan yang tak bisa dijaga bila
 * kedua sisi berdesakan di satu baris (lihat V89).
 */
@Entity
@Table(name = "fiber_connection_end")
class FiberConnectionEndJpaEntity(
    id: UUID,

    @Column(name = "connection_id", nullable = false, updatable = false)
    var connectionId: UUID,

    /** Salinan closure induk; ada demi unique index per-closure untuk core. */
    @Column(name = "closure_id", nullable = false, updatable = false)
    var closureId: UUID,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 1, updatable = false)
    var side: ConnectionSide,

    @Enumerated(EnumType.STRING)
    @Column(name = "point_kind", nullable = false, length = 20, updatable = false)
    var pointKind: ConnectionPointKind,

    @Column(name = "core_id", updatable = false)
    var coreId: UUID?,

    @Column(name = "node_id", updatable = false)
    var nodeId: UUID?,

    @Column(name = "port_number", updatable = false)
    var portNumber: Int?,
) : TenantAwareJpaEntity(id)

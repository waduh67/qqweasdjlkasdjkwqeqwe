package com.duluin.ftth.audit.adapter.outbound.persistence

import com.duluin.ftth.common.infrastructure.persistence.BaseJpaEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * Baris audit_log.
 *
 * SENGAJA bukan tenant-aware (`@TenantId`): entri ditulis pada transaksi terpisah
 * dengan tenant context diambil dari event; isolasi dijamin dua arah oleh
 * Row-Level Security (connection provider men-set `app.tenant_id`).
 *
 * `entityId` TIDAK dipetakan sebagai kolom sendiri — nilainya berformat UUID dan
 * memicu bug resolusi tipe di Hibernate 7 untuk atribut String; disimpan di dalam
 * [detail] (JSON) oleh adapter. [detail] adalah JSON string di kolom text.
 */
@Entity
@Table(name = "audit_log")
class AuditLogJpaEntity(
    id: UUID,

    @Column(name = "tenant_id", nullable = false, updatable = false)
    var tenantId: UUID,

    @Column(name = "actor_id")
    var actorId: UUID?,

    @Column(name = "actor_email")
    var actorEmail: String?,

    @Column(nullable = false, length = 80)
    var action: String,

    @Column(name = "entity_type", length = 80)
    var entityType: String?,

    @Column(columnDefinition = "text")
    var detail: String?,

    @Column(name = "occurred_at", nullable = false)
    var occurredAt: Instant,
) : BaseJpaEntity(id)

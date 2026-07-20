package com.duluin.ftth.common.infrastructure.persistence

import jakarta.persistence.Column
import jakarta.persistence.MappedSuperclass
import org.hibernate.annotations.TenantId
import java.util.UUID

/**
 * JPA entity yang di-scope per tenant.
 *
 * [TenantId] membuat Hibernate otomatis: (1) mengisi kolom `tenant_id` saat
 * insert dari `CurrentTenantIdentifierResolver`, dan (2) menambahkan predikat
 * `tenant_id = <tenant aktif>` pada setiap query. Lapisan kedua adalah
 * Row-Level Security di Postgres (lihat migration) — kalau kode lupa men-set
 * tenant, DB tetap tidak membocorkan data tenant lain.
 */
@MappedSuperclass
abstract class TenantAwareJpaEntity(id: UUID) : BaseJpaEntity(id) {

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    var tenantId: UUID? = null
        protected set
}

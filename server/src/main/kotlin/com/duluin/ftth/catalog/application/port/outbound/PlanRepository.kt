package com.duluin.ftth.catalog.application.port.outbound

import com.duluin.ftth.catalog.domain.model.Plan
import java.util.UUID

/**
 * Port persistence modul catalog. Tabel `plan` tenant-aware (@TenantId + RLS), jadi
 * semua pencarian ter-scope tenant aktif otomatis — tanpa parameter tenantId.
 */
interface PlanRepository {

    fun save(plan: Plan): Plan

    fun findById(id: UUID): Plan?

    /** Semua paket tenant aktif, terurut nama. */
    fun findAll(): List<Plan>

    fun existsByName(name: String): Boolean

    /** Cari paket menurut nama (abai huruf besar/kecil) — resolusi impor CSV per-nama. `null` bila tak ada. */
    fun findByNameIgnoreCase(name: String): Plan?
}

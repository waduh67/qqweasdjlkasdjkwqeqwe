package com.duluin.ftth.network.adapter.outbound.persistence

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.JpaSpecificationExecutor
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.UUID

/**
 * Hasil agregasi "berapa anak per induk". Projection berbasis alias, bukan
 * `Array<Any>`, supaya pemetaannya diperiksa compiler dan tidak bergantung pada
 * urutan kolom.
 */
interface ChildCount {
    val parentId: UUID
    val total: Long
}

/**
 * Repository Spring Data untuk agregat network.
 *
 * Semuanya `JpaSpecificationExecutor` karena pencarian di modul ini selalu
 * memadukan filter opsional (teks, area, induk) — lihat [NetworkSpecifications].
 * Dikumpulkan dalam satu berkas karena isinya deklarasi tanpa perilaku; adapter
 * yang menerjemahkan ke port domain tinggal di berkas masing-masing agregat.
 */

interface SiteJpaRepository : JpaRepository<SiteJpaEntity, UUID>, JpaSpecificationExecutor<SiteJpaEntity> {
    fun existsByCode(code: String): Boolean
}

interface OltJpaRepository : JpaRepository<OltJpaEntity, UUID>, JpaSpecificationExecutor<OltJpaEntity> {
    fun existsByCode(code: String): Boolean
    fun countBySiteId(siteId: UUID): Long

    @Query(
        """
        select o.siteId as parentId, count(o) as total from OltJpaEntity o
        where o.siteId in :siteIds group by o.siteId
        """,
    )
    fun countGroupedBySite(@Param("siteIds") siteIds: Collection<UUID>): List<ChildCount>
}

interface PonPortJpaRepository : JpaRepository<PonPortJpaEntity, UUID> {
    fun findByOltIdOrderByLabel(oltId: UUID): List<PonPortJpaEntity>
    fun existsByOltIdAndLabel(oltId: UUID, label: String): Boolean

    @Query(
        """
        select p.oltId as parentId, count(p) as total from PonPortJpaEntity p
        where p.oltId in :oltIds group by p.oltId
        """,
    )
    fun countGroupedByOlt(@Param("oltIds") oltIds: Collection<UUID>): List<ChildCount>
}

interface OdcJpaRepository : JpaRepository<OdcJpaEntity, UUID>, JpaSpecificationExecutor<OdcJpaEntity> {
    fun existsByCode(code: String): Boolean
    fun countByPonPortId(ponPortId: UUID): Long

    @Query(
        """
        select o.ponPortId as parentId, count(o) as total from OdcJpaEntity o
        where o.ponPortId in :ponPortIds group by o.ponPortId
        """,
    )
    fun countGroupedByPonPort(@Param("ponPortIds") ponPortIds: Collection<UUID>): List<ChildCount>
}

interface OdpJpaRepository : JpaRepository<OdpJpaEntity, UUID>, JpaSpecificationExecutor<OdpJpaEntity> {
    fun existsByCode(code: String): Boolean
    fun countByOdcId(odcId: UUID): Long
    fun findByOdcIdOrderByCode(odcId: UUID): List<OdpJpaEntity>

    @Query(
        """
        select o.odcId as parentId, count(o) as total from OdpJpaEntity o
        where o.odcId in :odcIds group by o.odcId
        """,
    )
    fun countGroupedByOdc(@Param("odcIds") odcIds: Collection<UUID>): List<ChildCount>
}

interface CableJpaRepository : JpaRepository<CableJpaEntity, UUID>, JpaSpecificationExecutor<CableJpaEntity> {
    fun existsByCode(code: String): Boolean
}

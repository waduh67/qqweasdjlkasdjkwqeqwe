package com.duluin.ftth.network.adapter.outbound.persistence

import com.duluin.ftth.network.domain.model.ConnectionPointKind
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
    fun findByCode(code: String): OltJpaEntity?
    fun findBySiteIdOrderByCode(siteId: UUID): List<OltJpaEntity>
    fun countBySiteId(siteId: UUID): Long

    @Query("select o.id from OltJpaEntity o")
    fun findAllIds(): Set<UUID>

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
    fun findByOltIdAndLabel(oltId: UUID, label: String): PonPortJpaEntity?
    fun existsByOltIdAndLabel(oltId: UUID, label: String): Boolean

    @Query(
        """
        select p.oltId as parentId, count(p) as total from PonPortJpaEntity p
        where p.oltId in :oltIds group by p.oltId
        """,
    )
    fun countGroupedByOlt(@Param("oltIds") oltIds: Collection<UUID>): List<ChildCount>
}

interface OdfJpaRepository : JpaRepository<OdfJpaEntity, UUID>, JpaSpecificationExecutor<OdfJpaEntity> {
    fun existsByCode(code: String): Boolean
    fun countBySiteId(siteId: UUID): Long
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

    @Query("select o.id from OdcJpaEntity o where o.ponPortId in :ponPortIds")
    fun findIdsByPonPortIds(@Param("ponPortIds") ponPortIds: Collection<UUID>): Set<UUID>

    fun findByPonPortIdOrderByCode(ponPortId: UUID): List<OdcJpaEntity>
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

    @Query("select o.id from OdpJpaEntity o where o.odcId in :odcIds")
    fun findIdsByOdcIds(@Param("odcIds") odcIds: Collection<UUID>): Set<UUID>
}

interface JointBoxJpaRepository :
    JpaRepository<JointBoxJpaEntity, UUID>,
    JpaSpecificationExecutor<JointBoxJpaEntity> {
    fun existsByCode(code: String): Boolean
}

interface CableJpaRepository : JpaRepository<CableJpaEntity, UUID>, JpaSpecificationExecutor<CableJpaEntity> {
    fun existsByCode(code: String): Boolean
}

interface CableCoreJpaRepository : JpaRepository<CableCoreJpaEntity, UUID> {
    fun findByCableIdOrderByCoreNumber(cableId: UUID): List<CableCoreJpaEntity>

    /** Dipakai saat jumlah core kabel dikurangi; core sisanya tak tersentuh. */
    fun deleteByCableIdAndCoreNumberGreaterThan(cableId: UUID, coreNumber: Int)
}

interface FiberConnectionJpaRepository : JpaRepository<FiberConnectionJpaEntity, UUID> {
    fun findByClosureId(closureId: UUID): List<FiberConnectionJpaEntity>
    fun countByClosureId(closureId: UUID): Long

    @Query(
        """
        select c.closureId as parentId, count(c) as total from FiberConnectionJpaEntity c
        where c.closureId in :closureIds group by c.closureId
        """,
    )
    fun countGroupedByClosure(@Param("closureIds") closureIds: Collection<UUID>): List<ChildCount>
}

interface FiberConnectionEndJpaRepository : JpaRepository<FiberConnectionEndJpaEntity, UUID> {
    fun findByConnectionIdIn(connectionIds: Collection<UUID>): List<FiberConnectionEndJpaEntity>
    fun findByClosureIdAndCoreId(closureId: UUID, coreId: UUID): FiberConnectionEndJpaEntity?
    fun findByCoreIdIn(coreIds: Collection<UUID>): List<FiberConnectionEndJpaEntity>
    fun deleteByConnectionIdIn(connectionIds: Collection<UUID>)

    /**
     * Semua ujung pada sebuah simpul non-core. Nomor port sengaja TIDAK ikut
     * disaring di sini: parameter bernilai null dalam JPQL menghasilkan `= null`
     * yang tak pernah cocok, sedangkan titik tak-bernomor (input splitter, PON,
     * ONU) justru bernomor null. Jumlah barisnya sekecil jumlah kaki splitter,
     * jadi penyaringan sisanya di Kotlin tak berbiaya.
     */
    fun findByPointKindAndNodeId(
        pointKind: ConnectionPointKind,
        nodeId: UUID,
    ): List<FiberConnectionEndJpaEntity>

    @Query(
        """
        select e from FiberConnectionEndJpaEntity e
        where e.coreId in (select c.id from CableCoreJpaEntity c where c.cableId = :cableId)
        """,
    )
    fun findByCableId(@Param("cableId") cableId: UUID): List<FiberConnectionEndJpaEntity>

    /**
     * Berapa PORT berbeda pada sebuah simpul yang sudah tersentuh sambungan.
     * `distinct` bukan hiasan: satu port ODF dipakai dua sisi, sedangkan yang
     * habis di rak adalah adapternya — menghitung sisi akan melaporkan rak
     * 24-port sebagai penuh padahal baru dua belas adapter terpakai.
     */
    @Query(
        """
        select count(distinct e.portNumber) from FiberConnectionEndJpaEntity e
        where e.pointKind = :kind and e.nodeId = :nodeId
        """,
    )
    fun countDistinctPorts(
        @Param("kind") kind: ConnectionPointKind,
        @Param("nodeId") nodeId: UUID,
    ): Long

    @Query(
        """
        select e.nodeId as parentId, count(distinct e.portNumber) as total
        from FiberConnectionEndJpaEntity e
        where e.pointKind = :kind and e.nodeId in :nodeIds group by e.nodeId
        """,
    )
    fun countDistinctPortsGrouped(
        @Param("kind") kind: ConnectionPointKind,
        @Param("nodeIds") nodeIds: Collection<UUID>,
    ): List<ChildCount>
}

package com.duluin.ftth.network.adapter.outbound.persistence

import com.duluin.ftth.common.domain.Page
import com.duluin.ftth.common.domain.PageRequest
import com.duluin.ftth.common.domain.geo.Coordinate
import com.duluin.ftth.common.infrastructure.persistence.geo.Geometries
import com.duluin.ftth.common.infrastructure.persistence.geo.toRoutePath
import com.duluin.ftth.common.infrastructure.persistence.toDomainPage
import com.duluin.ftth.common.infrastructure.persistence.toPageable
import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.network.application.port.outbound.CableRepository
import com.duluin.ftth.network.domain.model.Cable
import com.duluin.ftth.network.domain.model.CableAttachment
import com.duluin.ftth.network.domain.model.CableType
import com.duluin.ftth.network.domain.model.NetworkEndpoint
import com.duluin.ftth.network.domain.model.NetworkNodeRef
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import org.springframework.stereotype.Component
import java.util.UUID

@Component
class CablePersistenceAdapter(
    private val jpa: CableJpaRepository,
    private val attachmentJpa: CableAttachmentJpaRepository,
) : CableRepository {

    @PersistenceContext
    private lateinit var entityManager: EntityManager

    override fun save(cable: Cable): Cable {
        val entity = jpa.findById(cable.id).orElse(null)?.apply {
            code = cable.code
            name = cable.name
            cableType = cable.cableType
            coreCount = cable.coreCount
            route = Geometries.lineString(cable.route)
            lengthMeters = cable.lengthMeters
            status = cable.status
            installationMethod = cable.installation
            ownership = cable.ownership
        } ?: CableJpaEntity(
            id = cable.id,
            code = cable.code,
            name = cable.name,
            cableType = cable.cableType,
            coreCount = cable.coreCount,
            route = Geometries.lineString(cable.route),
            lengthMeters = cable.lengthMeters,
            status = cable.status,
            installationMethod = cable.installation,
            ownership = cable.ownership,
        )
        val saved = jpa.save(entity)
        saveAttachments(cable)
        return saved.toDomain(cable.attachments)
    }

    /**
     * Nomor urut ditulis dari POSISI di daftar, bukan dari bidang yang dibawa
     * domain — daftar terurut adalah satu-satunya sumber kebenaran urutan, dan
     * menyalinnya di sini menjaga keduanya tak pernah berbeda pendapat.
     */
    private fun saveAttachments(cable: Cable) {
        attachmentJpa.deleteRemoved(cable.id, cable.attachments.map { it.id })
        val existing = attachmentJpa.findByCableIdInOrderByCableIdAscSequenceAsc(listOf(cable.id))
            .associateBy { it.id }
        val entities = cable.attachments.mapIndexed { index, attachment ->
            existing[attachment.id]?.apply {
                sequence = index
                nodeKind = attachment.node.kind
                nodeId = attachment.node.id
                role = attachment.role
                ponPortId = attachment.node.ponPortId
                portNumber = attachment.node.portNumber
            } ?: CableAttachmentJpaEntity(
                id = attachment.id,
                cableId = cable.id,
                sequence = index,
                nodeKind = attachment.node.kind,
                nodeId = attachment.node.id,
                role = attachment.role,
                ponPortId = attachment.node.ponPortId,
                portNumber = attachment.node.portNumber,
            )
        }
        attachmentJpa.saveAll(entities)
    }

    override fun findById(id: UUID): Cable? = jpa.findById(id).orElse(null)?.let { hydrate(listOf(it)).first() }

    override fun findByIds(ids: Collection<UUID>): List<Cable> =
        if (ids.isEmpty()) emptyList() else hydrate(jpa.findAllById(ids))

    override fun search(query: String, cableType: CableType?, pageRequest: PageRequest): Page<Cable> {
        val spec = NetworkSpecifications.textMatches<CableJpaEntity>(query)
            .and(NetworkSpecifications.equals("cableType", cableType))
        val page = jpa.findAll(spec, pageRequest.toPageable()).toDomainPage()
        val hydrated = hydrate(page.content).associateBy { it.id }
        return page.map { hydrated.getValue(it.id) }
    }

    override fun findByEndpoint(node: NetworkNodeRef): List<Cable> =
        hydrate(jpa.findAll(NetworkSpecifications.endpointIs(node.kind, node.id)))

    override fun findByEndpointNodeIds(nodeIds: Set<UUID>): List<Cable> =
        if (nodeIds.isEmpty()) emptyList()
        else hydrate(jpa.findAll(NetworkSpecifications.endpointInNodes(nodeIds)))

    override fun findAttachedTo(nodeId: UUID): List<Cable> =
        findByIds(attachmentJpa.findCableIdsByNodeId(nodeId).distinct())

    /**
     * Memuat singgahan seluruh kabel dalam SATU query, bukan satu query per
     * kabel: daftar kabel sebuah ODC gampang berisi puluhan baris, dan pola
     * N+1 di sini terasa langsung di layar peta.
     */
    private fun hydrate(entities: List<CableJpaEntity>): List<Cable> {
        if (entities.isEmpty()) return emptyList()
        val byCable = attachmentJpa.findByCableIdInOrderByCableIdAscSequenceAsc(entities.map { it.id })
            .groupBy { it.cableId }
        return entities.map { entity -> entity.toDomain(byCable[entity.id].orEmpty().map { it.toDomain() }) }
    }

    /**
     * PENTING — dijalankan lewat [EntityManager], BUKAN `JdbcTemplate`. GUC
     * `app.tenant_id` yang menghidupkan Row-Level Security hanya menempel pada
     * connection pinjaman Hibernate; connection mentah dari pool akan ditolak RLS
     * dan hasilnya kosong tanpa penjelasan.
     *
     * Jarak diukur pada `geography` supaya satuannya meter sejati, bukan derajat —
     * di lintang Indonesia satu derajat bujur ±111 km, dan radius yang salah
     * satuan akan menyapu setengah kota.
     */
    override fun findPassing(location: Coordinate, radiusMeters: Double): List<Cable> {
        val ids = entityManager.createNativeQuery(
            """
            SELECT c.id::text FROM cable c
            WHERE ST_DWithin(
                c.route::geography,
                ST_SetSRID(ST_MakePoint(:lon, :lat), 4326)::geography,
                :radius
            )
            """.trimIndent(),
        )
            .setParameter("lon", location.longitude)
            .setParameter("lat", location.latitude)
            .setParameter("radius", radiusMeters)
            .resultList
            .map { UUID.fromString(it as String) }
        return findByIds(ids)
    }

    override fun existsByCode(code: String): Boolean = jpa.existsByCode(code)

    override fun deleteById(id: UUID) = jpa.deleteById(id)
}

internal fun CableJpaEntity.toDomain(attachments: List<CableAttachment>): Cable = Cable.rehydrate(
    id = id,
    tenantId = tenantId ?: TenantContext.tenantId(),
    code = code,
    name = name,
    cableType = cableType,
    coreCount = coreCount,
    route = route.toRoutePath(),
    attachments = attachments,
    status = status,
    installation = installationMethod,
    ownership = ownership,
)

internal fun CableAttachmentJpaEntity.toDomain(): CableAttachment = CableAttachment(
    id = id,
    node = NetworkEndpoint(nodeKind, nodeId, ponPortId = ponPortId, portNumber = portNumber),
    role = role,
)

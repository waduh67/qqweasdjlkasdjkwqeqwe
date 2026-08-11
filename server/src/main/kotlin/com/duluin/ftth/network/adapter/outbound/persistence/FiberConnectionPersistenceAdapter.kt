package com.duluin.ftth.network.adapter.outbound.persistence

import com.duluin.ftth.common.domain.UuidV7
import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.network.application.port.outbound.FiberConnectionRepository
import com.duluin.ftth.network.domain.model.ConnectionPoint
import com.duluin.ftth.network.domain.model.ConnectionPointKind
import com.duluin.ftth.network.domain.model.FiberConnection
import com.duluin.ftth.network.domain.model.OdfPortSide
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Menyusun agregat sambungan dari dua tabel: induk + dua barisnya di
 * `fiber_connection_end`. Sambungan yang kehilangan salah satu sisinya tak
 * pernah terbentuk — baris pincang seperti itu diabaikan, bukan dipaksakan jadi
 * sambungan sepihak yang menyesatkan penelusuran jalur.
 */
@Component
class FiberConnectionPersistenceAdapter(
    private val jpa: FiberConnectionJpaRepository,
    private val ends: FiberConnectionEndJpaRepository,
) : FiberConnectionRepository {

    override fun findById(id: UUID): FiberConnection? =
        jpa.findById(id).orElse(null)?.let { compose(listOf(it)).firstOrNull() }

    override fun findByClosureId(closureId: UUID): List<FiberConnection> =
        compose(jpa.findByClosureId(closureId))

    override fun countByClosureId(closureId: UUID): Long = jpa.countByClosureId(closureId)

    override fun countByClosureIds(closureIds: Set<UUID>): Map<UUID, Long> =
        if (closureIds.isEmpty()) emptyMap()
        else jpa.countGroupedByClosure(closureIds).associate { it.parentId to it.total }

    override fun findByCoreIds(coreIds: Collection<UUID>): List<FiberConnection> {
        if (coreIds.isEmpty()) return emptyList()
        return byEnds(ends.findByCoreIdIn(coreIds))
    }

    override fun findByCoreInClosure(closureId: UUID, coreId: UUID): FiberConnection? =
        ends.findByClosureIdAndCoreId(closureId, coreId)?.let { findById(it.connectionId) }

    override fun findByNodePoint(
        kind: ConnectionPointKind,
        nodeId: UUID,
        portNumber: Int?,
        portSide: OdfPortSide?,
    ): FiberConnection? =
        ends.findByPointKindAndNodeId(kind, nodeId)
            .firstOrNull { it.portNumber == portNumber && it.portSide == portSide }
            ?.let { findById(it.connectionId) }

    override fun countUsedPortsOfNode(kind: ConnectionPointKind, nodeId: UUID): Long =
        ends.countDistinctPorts(kind, nodeId)

    override fun countUsedPortsOfNodes(kind: ConnectionPointKind, nodeIds: Set<UUID>): Map<UUID, Long> =
        if (nodeIds.isEmpty()) emptyMap()
        else ends.countDistinctPortsGrouped(kind, nodeIds).associate { it.parentId to it.total }

    override fun usedPortNumbersOfNodes(kind: ConnectionPointKind, nodeIds: Set<UUID>): Map<UUID, Set<Int>> {
        if (nodeIds.isEmpty()) return emptyMap()
        return ends.findByPointKindAndNodeIdIn(kind, nodeIds)
            .mapNotNull { end -> end.nodeId?.let { node -> end.portNumber?.let { node to it } } }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, ports) -> ports.toSortedSet() }
    }

    override fun findByNodeIds(kind: ConnectionPointKind, nodeIds: Set<UUID>): List<FiberConnection> {
        if (nodeIds.isEmpty()) return emptyList()
        return byEnds(ends.findByPointKindAndNodeIdIn(kind, nodeIds))
    }

    override fun nodesWithPoint(kind: ConnectionPointKind, nodeIds: Set<UUID>): Set<UUID> {
        if (nodeIds.isEmpty()) return emptySet()
        return ends.findByPointKindAndNodeIdIn(kind, nodeIds).mapNotNullTo(HashSet()) { it.nodeId }
    }

    override fun findByCableId(cableId: UUID): List<FiberConnection> = byEnds(ends.findByCableId(cableId))

    override fun findByWorkOrderId(workOrderId: UUID): List<FiberConnection> =
        compose(jpa.findByWorkOrderIdOrderBySplicedAtAsc(workOrderId))

    /**
     * Ujung sambungan tak pernah berubah setelah dibuat — memindah serat berarti
     * memutus lalu menyambung lagi. Jadi penyimpanan ulang hanya menyentuh cara
     * pasang, redaman, catatan, dan work order tempat pekerjaannya dibukukan
     * (yang memang boleh menyusul).
     */
    override fun save(connection: FiberConnection): FiberConnection {
        val existing = jpa.findById(connection.id).orElse(null)
        if (existing != null) {
            existing.method = connection.method
            existing.lossDb = connection.lossDb
            existing.note = connection.note
            existing.workOrderId = connection.workOrderId
            jpa.save(existing)
            return connection
        }
        jpa.save(
            FiberConnectionJpaEntity(
                id = connection.id,
                closureKind = connection.closureKind,
                closureId = connection.closureId,
                method = connection.method,
                lossDb = connection.lossDb,
                note = connection.note,
                workOrderId = connection.workOrderId,
                splicedBy = connection.splicedBy,
                splicedAt = connection.splicedAt,
            ),
        )
        // Induk lebih dulu: sisi ber-foreign-key gabungan ke (id, closure_id)-nya.
        ends.saveAll(
            listOf(
                connection.endEntity(ConnectionSide.A, connection.a),
                connection.endEntity(ConnectionSide.B, connection.b),
            ),
        )
        return connection
    }

    override fun deleteAll(connections: List<FiberConnection>) {
        if (connections.isEmpty()) return
        val ids = connections.map { it.id }
        // Sisi dulu, baru induknya: DB memang meng-cascade, tapi menghapusnya
        // lewat JPA menjaga persistence context tak memegang baris hantu.
        ends.deleteByConnectionIdIn(ids)
        jpa.deleteAllById(ids)
    }

    private fun byEnds(matched: List<FiberConnectionEndJpaEntity>): List<FiberConnection> {
        if (matched.isEmpty()) return emptyList()
        return compose(jpa.findAllById(matched.map { it.connectionId }.distinct()))
    }

    private fun compose(parents: List<FiberConnectionJpaEntity>): List<FiberConnection> {
        if (parents.isEmpty()) return emptyList()
        val bySide = ends.findByConnectionIdIn(parents.map { it.id })
            .groupBy { it.connectionId }
        return parents.mapNotNull { parent ->
            val sides = bySide[parent.id]?.associateBy { it.side } ?: return@mapNotNull null
            val a = sides[ConnectionSide.A] ?: return@mapNotNull null
            val b = sides[ConnectionSide.B] ?: return@mapNotNull null
            FiberConnection.rehydrate(
                id = parent.id,
                tenantId = parent.tenantId ?: TenantContext.tenantId(),
                closureKind = parent.closureKind,
                closureId = parent.closureId,
                a = a.toPoint(),
                b = b.toPoint(),
                method = parent.method,
                lossDb = parent.lossDb,
                note = parent.note,
                workOrderId = parent.workOrderId,
                splicedBy = parent.splicedBy,
                splicedAt = parent.splicedAt,
            )
        }
    }
}

private fun FiberConnection.endEntity(side: ConnectionSide, point: ConnectionPoint) = FiberConnectionEndJpaEntity(
    id = UuidV7.generate(),
    connectionId = id,
    closureId = closureId,
    side = side,
    pointKind = point.kind,
    coreId = point.coreId,
    nodeId = point.nodeId,
    portNumber = point.portNumber,
    portSide = point.portSide,
)

private fun FiberConnectionEndJpaEntity.toPoint() = ConnectionPoint(
    kind = pointKind,
    coreId = coreId,
    nodeId = nodeId,
    portNumber = portNumber,
    portSide = portSide,
)

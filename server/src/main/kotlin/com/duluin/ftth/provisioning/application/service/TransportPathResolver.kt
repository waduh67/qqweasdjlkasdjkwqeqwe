package com.duluin.ftth.provisioning.application.service

import com.duluin.ftth.common.domain.error.ValidationException
import com.duluin.ftth.provisioning.application.port.outbound.TransportTopologyRepository
import com.duluin.ftth.provisioning.domain.model.AdministrativeStatus
import com.duluin.ftth.provisioning.domain.model.InterfaceRole
import com.duluin.ftth.provisioning.domain.model.ManagedInterface
import com.duluin.ftth.provisioning.domain.model.ManagedNode
import com.duluin.ftth.provisioning.domain.model.ManagedNodeRole
import com.duluin.ftth.provisioning.domain.model.TransportLink
import com.duluin.ftth.provisioning.domain.model.TransportTopologySnapshot
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

enum class TransportPathValidationCode {
    INVALID_PATH_START,
    CROSS_TENANT_PATH,
    DISCONNECTED_PATH,
    CYCLIC_PATH,
    AMBIGUOUS_PATH,
    TOPOLOGY_DISABLED,
    ADMINISTRATIVELY_EXCLUDED,
    STALE_TOPOLOGY,
}

data class TransportPathHop(
    val link: TransportLink,
    val fromInterface: ManagedInterface,
    val toInterface: ManagedInterface,
)

data class ResolvedTransportPath(
    val nodes: List<ManagedNode>,
    val hops: List<TransportPathHop>,
)

@Service
class TransportPathResolver(
    private val topology: TransportTopologyRepository,
    private val clock: Clock,
    @Value("\${ftth.provisioning.topology-max-observation-age:PT5M}")
    private val maxObservationAge: Duration,
) {
    @Transactional(readOnly = true)
    fun resolve(tenantId: UUID, oltAccessInterfaceId: UUID): ResolvedTransportPath {
        val snapshot = topology.snapshot()
        validateTenantOwnership(tenantId, snapshot)

        val nodes = snapshot.nodes.associateBy { it.id }
        val interfaces = snapshot.interfaces.associateBy { it.id }
        val startInterface = interfaces[oltAccessInterfaceId]
            ?: reject(TransportPathValidationCode.INVALID_PATH_START)
        val startNode = nodes[startInterface.nodeId]
            ?: reject(TransportPathValidationCode.INVALID_PATH_START)
        if (startInterface.role != InterfaceRole.ACCESS || startNode.role != ManagedNodeRole.OLT) {
            reject(TransportPathValidationCode.INVALID_PATH_START)
        }

        val edges = snapshot.links.map { link ->
            val interfaceA = interfaces[link.interfaceAId]
                ?: reject(TransportPathValidationCode.DISCONNECTED_PATH)
            val interfaceZ = interfaces[link.interfaceZId]
                ?: reject(TransportPathValidationCode.DISCONNECTED_PATH)
            val nodeA = nodes[interfaceA.nodeId]
                ?: reject(TransportPathValidationCode.DISCONNECTED_PATH)
            val nodeZ = nodes[interfaceZ.nodeId]
                ?: reject(TransportPathValidationCode.DISCONNECTED_PATH)
            Edge(link, interfaceA, interfaceZ, nodeA, nodeZ)
        }
        val adjacency = edges
            .flatMap { edge -> listOf(edge.nodeA.id to edge, edge.nodeZ.id to edge) }
            .groupBy({ it.first }, { it.second })

        val paths = findPaths(startNode, startInterface, adjacency)
        if (paths.size > 1) reject(TransportPathValidationCode.AMBIGUOUS_PATH)
        if (hasReachableCycle(startNode, startInterface, adjacency)) {
            reject(TransportPathValidationCode.CYCLIC_PATH)
        }
        val selectedEdges = paths.singleOrNull()
            ?: reject(TransportPathValidationCode.DISCONNECTED_PATH)
        val resolved = orderPath(startNode, selectedEdges)
        validateCurrentPath(resolved)
        return resolved
    }

    private fun validateTenantOwnership(tenantId: UUID, snapshot: TransportTopologySnapshot) {
        val hasForeignRecord = snapshot.nodes.any { it.tenantId != tenantId } ||
            snapshot.interfaces.any { it.tenantId != tenantId } ||
            snapshot.links.any { it.tenantId != tenantId }
        if (hasForeignRecord) reject(TransportPathValidationCode.CROSS_TENANT_PATH)
    }

    private fun findPaths(
        startNode: ManagedNode,
        startInterface: ManagedInterface,
        adjacency: Map<UUID, List<Edge>>,
    ): List<List<Edge>> {
        val paths = mutableListOf<List<Edge>>()
        val visited = mutableSetOf(startNode.id)
        val currentPath = mutableListOf<Edge>()

        fun visit(node: ManagedNode) {
            if (paths.size > 1) return
            if (node.id != startNode.id && node.role == ManagedNodeRole.BRAS) {
                paths += currentPath.toList()
                return
            }
            orderedEdges(node.id, startNode.id, startInterface.id, currentPath.isEmpty(), adjacency).forEach { edge ->
                val next = edge.otherNode(node.id)
                if (!visited.add(next.id)) return@forEach
                currentPath += edge
                visit(next)
                currentPath.removeLast()
                visited.remove(next.id)
            }
        }

        visit(startNode)
        return paths
    }

    private fun hasReachableCycle(
        startNode: ManagedNode,
        startInterface: ManagedInterface,
        adjacency: Map<UUID, List<Edge>>,
    ): Boolean {
        val visited = mutableSetOf<UUID>()

        fun visit(node: ManagedNode, parentLinkId: UUID?, root: Boolean): Boolean {
            visited += node.id
            if (!root && node.role == ManagedNodeRole.BRAS) return false
            return orderedEdges(node.id, startNode.id, startInterface.id, root, adjacency).any { edge ->
                if (edge.link.id == parentLinkId) return@any false
                val next = edge.otherNode(node.id)
                next.id in visited || visit(next, edge.link.id, false)
            }
        }

        return visit(startNode, null, true)
    }

    private fun orderedEdges(
        nodeId: UUID,
        startNodeId: UUID,
        startInterfaceId: UUID,
        atPathStart: Boolean,
        adjacency: Map<UUID, List<Edge>>,
    ): List<Edge> = adjacency[nodeId].orEmpty()
        .asSequence()
        .filter { !atPathStart || nodeId != startNodeId || it.containsInterface(startInterfaceId) }
        .sortedWith(compareBy<Edge>({ it.otherNode(nodeId).id.toString() }, { it.link.id.toString() }))
        .toList()

    private fun orderPath(startNode: ManagedNode, edges: List<Edge>): ResolvedTransportPath {
        val nodes = mutableListOf(startNode)
        val hops = mutableListOf<TransportPathHop>()
        var current = startNode
        edges.forEach { edge ->
            val next = edge.otherNode(current.id)
            hops += TransportPathHop(
                edge.link,
                edge.interfaceFor(current.id),
                edge.interfaceFor(next.id),
            )
            nodes += next
            current = next
        }
        if (nodes.drop(1).dropLast(1).any {
                it.role !in setOf(ManagedNodeRole.ACCESS_SWITCH, ManagedNodeRole.AGGREGATION_SWITCH)
            }
        ) {
            reject(TransportPathValidationCode.DISCONNECTED_PATH)
        }
        return ResolvedTransportPath(nodes, hops)
    }

    private fun validateCurrentPath(path: ResolvedTransportPath) {
        val statuses = buildList {
            addAll(path.nodes.map { it.administrativeStatus })
            addAll(path.hops.flatMap { listOf(it.fromInterface.administrativeStatus, it.toInterface.administrativeStatus) })
            addAll(path.hops.map { it.link.administrativeStatus })
        }
        if (AdministrativeStatus.EXCLUDED in statuses) {
            reject(TransportPathValidationCode.ADMINISTRATIVELY_EXCLUDED)
        }
        if (AdministrativeStatus.DISABLED in statuses) {
            reject(TransportPathValidationCode.TOPOLOGY_DISABLED)
        }

        val observations = buildList {
            addAll(path.nodes.map { it.observedAt })
            addAll(path.hops.flatMap { listOf(it.fromInterface.observedAt, it.toInterface.observedAt) })
            addAll(path.hops.map { it.link.observedAt })
        }
        val now = clock.instant()
        val oldestAccepted = now.minus(maxObservationAge)
        if (observations.any { it.isBefore(oldestAccepted) || it.isAfter(now) }) {
            reject(TransportPathValidationCode.STALE_TOPOLOGY)
        }
    }

    private fun reject(code: TransportPathValidationCode): Nothing = throw ValidationException(code.name)

    private data class Edge(
        val link: TransportLink,
        val interfaceA: ManagedInterface,
        val interfaceZ: ManagedInterface,
        val nodeA: ManagedNode,
        val nodeZ: ManagedNode,
    ) {
        fun containsInterface(interfaceId: UUID) = interfaceA.id == interfaceId || interfaceZ.id == interfaceId

        fun otherNode(nodeId: UUID): ManagedNode = when (nodeId) {
            nodeA.id -> nodeZ
            nodeZ.id -> nodeA
            else -> throw IllegalArgumentException("Edge is not connected to node")
        }

        fun interfaceFor(nodeId: UUID): ManagedInterface = when (nodeId) {
            nodeA.id -> interfaceA
            nodeZ.id -> interfaceZ
            else -> throw IllegalArgumentException("Edge is not connected to node")
        }
    }
}

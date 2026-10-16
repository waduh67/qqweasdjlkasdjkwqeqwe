package com.karuhun.ftth.provisioning.application.port.outbound

import com.karuhun.ftth.provisioning.domain.model.ManagedInterface
import com.karuhun.ftth.provisioning.domain.model.ManagedNode
import com.karuhun.ftth.provisioning.domain.model.TransportLink
import com.karuhun.ftth.provisioning.domain.model.TransportTopologySnapshot
import java.util.UUID

interface TransportTopologyRepository {
    fun saveNode(value: ManagedNode): ManagedNode
    fun saveInterface(value: ManagedInterface): ManagedInterface
    fun saveLink(value: TransportLink): TransportLink
    fun snapshot(): TransportTopologySnapshot
    fun deleteNode(id: UUID): Unit = error("TOPOLOGY_NODE_DELETE_UNSUPPORTED")
    fun deleteInterface(id: UUID): Unit = error("TOPOLOGY_INTERFACE_DELETE_UNSUPPORTED")
    fun deleteLink(id: UUID): Unit = error("TOPOLOGY_LINK_DELETE_UNSUPPORTED")
}

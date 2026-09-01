package com.duluin.ftth.provisioning.application.port.outbound

import com.duluin.ftth.provisioning.domain.model.ManagedInterface
import com.duluin.ftth.provisioning.domain.model.ManagedNode
import com.duluin.ftth.provisioning.domain.model.TransportLink
import com.duluin.ftth.provisioning.domain.model.TransportTopologySnapshot

interface TransportTopologyRepository {
    fun saveNode(value: ManagedNode): ManagedNode
    fun saveInterface(value: ManagedInterface): ManagedInterface
    fun saveLink(value: TransportLink): TransportLink
    fun snapshot(): TransportTopologySnapshot
}

package com.duluin.ftth.inventory.application.port.outbound

import com.duluin.ftth.common.security.AuthorityScope
import com.duluin.ftth.inventory.WarehousePage
import com.duluin.ftth.inventory.application.port.inbound.*
import java.util.UUID

interface WarehouseMasterStore {
    fun lockTopology()
    fun get(kind: MasterKind, id: UUID, lock: Boolean = false): MasterSnapshot
    fun list(kind: MasterKind, filter: MasterFilter, locations: AuthorityScope, areas: AuthorityScope, sites: Map<UUID, UUID?>): WarehousePage<MasterSnapshot>
    fun save(kind: MasterKind, id: UUID, input: MasterInput, existing: MasterSnapshot?): MasterSnapshot
    fun hasReferences(kind: MasterKind, id: UUID): Boolean
    fun grantCreator(location: UUID, actor: UUID, epoch: Long)
    fun lookup(serial: String, mac: String?, locations: AuthorityScope, areas: AuthorityScope, provenance: Boolean, sites: Map<UUID, UUID?>): IdentityLookupSnapshot
}

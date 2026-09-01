package com.duluin.ftth.provisioning.adapter.outbound.persistence

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface ManagedNodeJpaRepository : JpaRepository<ManagedNodeJpaEntity, UUID>

interface ManagedInterfaceJpaRepository : JpaRepository<ManagedInterfaceJpaEntity, UUID>

interface TransportLinkJpaRepository : JpaRepository<TransportLinkJpaEntity, UUID>

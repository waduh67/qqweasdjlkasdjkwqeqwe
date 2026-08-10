package com.duluin.ftth.helpdesk.adapter.outbound.persistence

import com.duluin.ftth.helpdesk.domain.model.TicketStatus
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.JpaSpecificationExecutor
import org.springframework.data.jpa.repository.Query
import java.util.UUID

/** Cacah tiket per status — kartu antrean tanpa menarik barisnya. */
interface TicketStatusCount {
    val status: TicketStatus
    val total: Long
}

interface TicketJpaRepository :
    JpaRepository<TicketJpaEntity, UUID>,
    JpaSpecificationExecutor<TicketJpaEntity> {

    fun findByCustomerIdOrderByOpenedAtDesc(customerId: UUID): List<TicketJpaEntity>

    fun countByCustomerIdAndStatusNot(customerId: UUID, status: TicketStatus): Long

    @Query("select t.status as status, count(t) as total from TicketJpaEntity t group by t.status")
    fun countGroupedByStatus(): List<TicketStatusCount>
}

interface TicketMessageJpaRepository : JpaRepository<TicketMessageJpaEntity, UUID> {
    fun findByTicketIdOrderByAt(ticketId: UUID): List<TicketMessageJpaEntity>
}

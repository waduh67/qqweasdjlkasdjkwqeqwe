package com.duluin.ftth.order.application.port.outbound

import com.duluin.ftth.common.domain.Page
import com.duluin.ftth.common.domain.PageRequest
import com.duluin.ftth.order.domain.model.LeadSource
import com.duluin.ftth.order.domain.model.LeadStatus
import com.duluin.ftth.order.domain.model.OrderLead
import java.util.UUID

interface OrderLeadRepository {
    fun save(lead: OrderLead)
    fun find(id: UUID): OrderLead?
    fun search(filter: OrderLeadFilter, pageRequest: PageRequest): Page<OrderLead>
    /** Prospek yang nomornya sama — dipakai operator untuk menghindari membuat duplikat. */
    fun findByPhone(phone: String): List<OrderLead>
}

/**
 * [query] dicocokkan ke nama ATAU nomor HP; operator mencari dengan apa pun yang disebut
 * penelepon. Field null = tanpa penyaringan.
 */
data class OrderLeadFilter(
    val query: String? = null,
    val status: LeadStatus? = null,
    val source: LeadSource? = null,
)

package com.duluin.ftth.order.adapter.outbound.persistence

import com.duluin.ftth.common.domain.Page
import com.duluin.ftth.common.domain.PageRequest
import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.order.application.port.outbound.OrderLeadFilter
import com.duluin.ftth.order.application.port.outbound.OrderLeadRepository
import com.duluin.ftth.order.domain.model.LeadSource
import com.duluin.ftth.order.domain.model.LeadStatus
import com.duluin.ftth.order.domain.model.OrderLead
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import org.springframework.stereotype.Component
import java.util.UUID

@Component
class OrderLeadPersistenceAdapter(
    private val leads: OrderLeadJpaRepository,
) : OrderLeadRepository {

    @PersistenceContext private lateinit var entityManager: EntityManager

    override fun save(lead: OrderLead) {
        val entity = leads.findById(lead.id).orElse(null)?.apply {
            name = lead.name
            phone = lead.phone
            email = lead.email
            address = lead.address
            latitude = lead.latitude
            longitude = lead.longitude
            interestedPlanId = lead.interestedPlanId
            status = lead.status.name
            convertedCustomerId = lead.convertedCustomerId
            notes = lead.notes
        } ?: OrderLeadJpaEntity(
            lead.id, lead.name, lead.phone, lead.email, lead.address, lead.latitude, lead.longitude,
            lead.interestedPlanId, lead.source.name, lead.status.name, lead.convertedCustomerId, lead.notes,
        )
        leads.save(entity)
    }

    override fun find(id: UUID): OrderLead? = leads.findById(id).orElse(null)?.toDomain()

    override fun findByPhone(phone: String): List<OrderLead> =
        leads.findAllByPhoneOrderByCreatedAtDesc(phone).map { it.toDomain() }

    /**
     * JPQL, bukan native: penyaringannya sederhana dan Hibernate menambahkan predikat tenant
     * dari `@TenantId` secara otomatis. Native query akan kehilangan lapisan itu dan hanya
     * bersandar pada RLS.
     *
     * `query` dicocokkan ke nama ATAU nomor HP — operator mencari dengan apa pun yang disebut
     * penelepon, dan keduanya sudah dinormalkan di domain sehingga "0812-3456" tak meleset.
     */
    override fun search(filter: OrderLeadFilter, pageRequest: PageRequest): Page<OrderLead> {
        val conditions = buildList {
            if (!filter.query.isNullOrBlank()) add("(lower(l.name) like :q or l.phone like :q)")
            if (filter.status != null) add("l.status = :status")
            if (filter.source != null) add("l.source = :source")
        }
        val where = if (conditions.isEmpty()) "" else " where " + conditions.joinToString(" and ")
        val total = entityManager.createQuery(
            "select count(l) from OrderLeadJpaEntity l$where",
            Long::class.javaObjectType,
        ).applyFilter(filter).singleResult
        if (total == 0L) return Page(emptyList(), pageRequest.page, pageRequest.size, 0)
        val rows = entityManager.createQuery(
            "select l from OrderLeadJpaEntity l$where order by l.createdAt desc, l.id desc",
            OrderLeadJpaEntity::class.java,
        ).applyFilter(filter)
            .setFirstResult(pageRequest.page * pageRequest.size)
            .setMaxResults(pageRequest.size)
            .resultList
        return Page(rows.map { it.toDomain() }, pageRequest.page, pageRequest.size, total)
    }

    private fun <T> jakarta.persistence.TypedQuery<T>.applyFilter(filter: OrderLeadFilter): jakarta.persistence.TypedQuery<T> {
        if (!filter.query.isNullOrBlank()) setParameter("q", "%${filter.query.trim().lowercase()}%")
        filter.status?.let { setParameter("status", it.name) }
        filter.source?.let { setParameter("source", it.name) }
        return this
    }

    private fun OrderLeadJpaEntity.toDomain() = OrderLead.rehydrate(
        id = id,
        tenantId = tenantId ?: TenantContext.tenantId(),
        name = name,
        phone = phone,
        email = email,
        address = address,
        latitude = latitude,
        longitude = longitude,
        interestedPlanId = interestedPlanId,
        source = LeadSource.valueOf(source),
        status = LeadStatus.valueOf(status),
        convertedCustomerId = convertedCustomerId,
        notes = notes,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )
}

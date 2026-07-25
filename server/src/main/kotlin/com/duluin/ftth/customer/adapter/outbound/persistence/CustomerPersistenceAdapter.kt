package com.duluin.ftth.customer.adapter.outbound.persistence

import com.duluin.ftth.common.domain.Page
import com.duluin.ftth.common.domain.PageRequest
import com.duluin.ftth.common.infrastructure.persistence.geo.Geometries
import com.duluin.ftth.common.infrastructure.persistence.geo.toCoordinate
import com.duluin.ftth.common.infrastructure.persistence.toDomainPage
import com.duluin.ftth.common.infrastructure.persistence.toPageable
import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.customer.application.port.outbound.CustomerRepository
import com.duluin.ftth.customer.domain.model.Customer
import com.duluin.ftth.customer.domain.model.CustomerStatus
import org.springframework.data.jpa.domain.Specification
import org.springframework.stereotype.Component
import java.util.UUID

@Component
class CustomerPersistenceAdapter(
    private val jpa: CustomerJpaRepository,
) : CustomerRepository {

    override fun save(customer: Customer): Customer {
        val entity = jpa.findById(customer.id).orElse(null)?.apply {
            name = customer.name
            phone = customer.phone
            email = customer.email
            address = customer.address
            location = Geometries.point(customer.location)
            areaId = customer.areaId
            status = customer.status
        } ?: CustomerJpaEntity(
            id = customer.id,
            code = customer.code,
            name = customer.name,
            phone = customer.phone,
            email = customer.email,
            address = customer.address,
            location = Geometries.point(customer.location),
            areaId = customer.areaId,
            status = customer.status,
        )
        return jpa.save(entity).toDomain()
    }

    override fun findById(id: UUID): Customer? = jpa.findById(id).orElse(null)?.toDomain()

    override fun findAllByIds(ids: Set<UUID>): List<Customer> =
        if (ids.isEmpty()) emptyList() else jpa.findAllById(ids).map { it.toDomain() }

    override fun findAwaitingInstallation(areaIds: Set<UUID>?): List<Customer> = when {
        areaIds == null -> jpa.findAwaitingInstallation(CustomerStatus.TERMINATED)
        areaIds.isEmpty() -> emptyList()
        else -> jpa.findAwaitingInstallationInAreas(CustomerStatus.TERMINATED, areaIds)
    }.map { it.toDomain() }

    override fun search(
        query: String,
        areaIds: Set<UUID>?,
        status: CustomerStatus?,
        pageRequest: PageRequest,
    ): Page<Customer> {
        val spec = matchesText(query).and(withinAreas(areaIds)).and(hasStatus(status))
        return jpa.findAll(spec, pageRequest.toPageable()).toDomainPage().map { it.toDomain() }
    }

    override fun existsByCode(code: String): Boolean = jpa.existsByCode(code)

    override fun deleteById(id: UUID) = jpa.deleteById(id)

    /**
     * Pencarian pelanggan mencakup nomor telepon dan alamat, bukan hanya nama —
     * di helpdesk, penelepon lebih sering mengenali dirinya lewat nomor atau
     * alamat daripada lewat kode pelanggan.
     */
    private fun matchesText(query: String) = Specification<CustomerJpaEntity> { root, _, cb ->
        val needle = query.trim().lowercase()
        if (needle.isEmpty()) {
            cb.conjunction()
        } else {
            val pattern = "%$needle%"
            cb.or(
                cb.like(cb.lower(root.get("code")), pattern),
                cb.like(cb.lower(root.get("name")), pattern),
                cb.like(cb.lower(root.get("address")), pattern),
                cb.like(cb.lower(cb.coalesce(root.get("phone"), "")), pattern),
            )
        }
    }

    /** Set kosong berarti nol hasil, bukan tanpa filter — lihat NetworkSpecifications. */
    private fun withinAreas(areaIds: Set<UUID>?) = Specification<CustomerJpaEntity> { root, _, cb ->
        when {
            areaIds == null -> cb.conjunction()
            areaIds.isEmpty() -> cb.disjunction()
            else -> root.get<UUID?>("areaId").`in`(areaIds)
        }
    }

    private fun hasStatus(status: CustomerStatus?) = Specification<CustomerJpaEntity> { root, _, cb ->
        if (status == null) cb.conjunction() else cb.equal(root.get<CustomerStatus>("status"), status)
    }
}

internal fun CustomerJpaEntity.toDomain(): Customer = Customer.rehydrate(
    id = id,
    tenantId = tenantId ?: TenantContext.tenantId(),
    code = code,
    name = name,
    phone = phone,
    email = email,
    address = address,
    location = location.toCoordinate(),
    areaId = areaId,
    status = status,
)

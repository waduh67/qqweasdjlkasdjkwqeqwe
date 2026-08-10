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
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import org.springframework.data.jpa.domain.Specification
import org.springframework.stereotype.Component
import java.util.UUID

@Component
class CustomerPersistenceAdapter(
    private val jpa: CustomerJpaRepository,
) : CustomerRepository {

    @PersistenceContext
    private lateinit var entityManager: EntityManager

    override fun save(customer: Customer): Customer {
        val entity = jpa.findById(customer.id).orElse(null)?.apply {
            name = customer.name
            phone = customer.phone
            email = customer.email
            address = customer.address
            location = Geometries.point(customer.location)
            areaId = customer.areaId
            idCardNumber = customer.idCardNumber
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
            idCardNumber = customer.idCardNumber,
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

    /**
     * Satu-satunya kueri native di module ini, dan alasannya sempit: predikatnya
     * membandingkan **isi geometri** (`ST_X`/`ST_Y`), yang tak punya padanan di JPQL
     * maupun Criteria. Lewat [EntityManager] — sama seperti renderer tile — supaya
     * RLS tetap mendapat GUC tenant-nya; `SELECT *` dipetakan balik ke entitas
     * sehingga tak ada pemetaan kolom manual yang bisa basi saat skema berubah.
     *
     * TERMINATED dibuang: menaruh titik pelanggan yang sudah putus di peta hanya
     * meramaikan daftar pilihan tanpa ada yang akan memasangnya.
     */
    override fun findUnmapped(query: String, areaIds: Set<UUID>?, limit: Int): List<Customer> {
        if (areaIds != null && areaIds.isEmpty()) return emptyList()
        val needle = query.trim().lowercase()
        val areaFilter = if (areaIds == null) "" else "AND c.area_id::text = ANY(string_to_array(:areaIds, ','))"
        val textFilter = if (needle.isEmpty()) {
            ""
        } else {
            """
            AND (lower(c.code) LIKE :needle OR lower(c.name) LIKE :needle
                 OR lower(c.address) LIKE :needle OR lower(COALESCE(c.phone, '')) LIKE :needle)
            """.trimIndent()
        }
        val sql = """
            SELECT * FROM customer c
            WHERE ST_X(c.location) = 0 AND ST_Y(c.location) = 0
              AND c.status <> 'TERMINATED'
              $areaFilter
              $textFilter
            ORDER BY c.code
        """.trimIndent()
        val jpaQuery = entityManager.createNativeQuery(sql, CustomerJpaEntity::class.java)
            .setMaxResults(limit)
        if (areaIds != null) jpaQuery.setParameter("areaIds", areaIds.joinToString(",") { it.toString() })
        if (needle.isNotEmpty()) jpaQuery.setParameter("needle", "%$needle%")

        @Suppress("UNCHECKED_CAST")
        return (jpaQuery.resultList as List<CustomerJpaEntity>).map { it.toDomain() }
    }

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

    override fun count(): Long = jpa.count()

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
    idCardNumber = idCardNumber,
    status = status,
)

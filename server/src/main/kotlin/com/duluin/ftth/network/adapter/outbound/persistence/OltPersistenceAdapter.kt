package com.duluin.ftth.network.adapter.outbound.persistence

import com.duluin.ftth.common.domain.Page
import com.duluin.ftth.common.domain.PageRequest
import com.duluin.ftth.common.infrastructure.persistence.toDomainPage
import com.duluin.ftth.common.infrastructure.persistence.toPageable
import com.duluin.ftth.common.security.SecretCipher
import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.network.application.port.outbound.OltRepository
import com.duluin.ftth.network.domain.model.Olt
import com.duluin.ftth.network.domain.model.vo.ManagementIp
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Adapter OLT sekaligus batas enkripsi kredensial: domain memegang community
 * string apa adanya, database hanya pernah melihat ciphertext.
 */
@Component
class OltPersistenceAdapter(
    private val jpa: OltJpaRepository,
    private val cipher: SecretCipher,
) : OltRepository {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun save(olt: Olt): Olt {
        val encryptedCommunity = olt.snmpCommunity?.let(cipher::encrypt)
        val entity = jpa.findById(olt.id).orElse(null)?.apply {
            siteId = olt.siteId
            name = olt.name
            vendor = olt.vendor
            model = olt.model
            managementIp = olt.managementIp?.value
            snmpCommunity = encryptedCommunity
            status = olt.status
        } ?: OltJpaEntity(
            id = olt.id,
            code = olt.code,
            siteId = olt.siteId,
            name = olt.name,
            vendor = olt.vendor,
            model = olt.model,
            managementIp = olt.managementIp?.value,
            snmpCommunity = encryptedCommunity,
            status = olt.status,
        )
        return jpa.save(entity).toDomain()
    }

    override fun findById(id: UUID): Olt? = jpa.findById(id).orElse(null)?.toDomain()

    override fun findAllByIds(ids: Set<UUID>): List<Olt> = jpa.findAllById(ids).map { it.toDomain() }

    override fun search(query: String, siteId: UUID?, pageRequest: PageRequest): Page<Olt> {
        val spec = NetworkSpecifications.textMatches<OltJpaEntity>(query)
            .and(NetworkSpecifications.equals("siteId", siteId))
        return jpa.findAll(spec, pageRequest.toPageable()).toDomainPage().map { it.toDomain() }
    }

    override fun existsByCode(code: String): Boolean = jpa.existsByCode(code)

    override fun countBySiteId(siteId: UUID): Long = jpa.countBySiteId(siteId)

    override fun countBySiteIds(siteIds: Set<UUID>): Map<UUID, Long> =
        if (siteIds.isEmpty()) emptyMap()
        else jpa.countGroupedBySite(siteIds).associate { it.parentId to it.total }

    override fun deleteById(id: UUID) = jpa.deleteById(id)

    private fun OltJpaEntity.toDomain(): Olt = Olt.rehydrate(
        id = id,
        tenantId = tenantId ?: TenantContext.tenantId(),
        siteId = siteId,
        code = code,
        name = name,
        vendor = vendor,
        model = model,
        managementIp = ManagementIp.ofNullable(managementIp),
        snmpCommunity = decryptQuietly(snmpCommunity, code),
        status = status,
    )

    /**
     * Kredensial yang tidak bisa didekripsi (mis. kunci enkripsi dirotasi tanpa
     * migrasi data) tidak boleh membuat seluruh daftar OLT gagal dimuat. OLT-nya
     * tetap tampil, hanya kehilangan kredensial sehingga ditandai "belum
     * termonitor" dan bisa diisi ulang operator.
     */
    private fun decryptQuietly(ciphertext: String?, oltCode: String): String? {
        if (ciphertext == null) return null
        return runCatching { cipher.decrypt(ciphertext) }
            .onFailure { log.warn("Kredensial SNMP OLT {} tidak bisa didekripsi; perlu diisi ulang", oltCode) }
            .getOrNull()
    }
}

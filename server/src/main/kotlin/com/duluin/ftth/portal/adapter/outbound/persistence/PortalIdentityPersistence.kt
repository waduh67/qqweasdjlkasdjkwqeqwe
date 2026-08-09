package com.duluin.ftth.portal.adapter.outbound.persistence

import com.duluin.ftth.common.domain.UuidV7
import com.duluin.ftth.common.infrastructure.persistence.BaseJpaEntity
import com.duluin.ftth.portal.application.port.outbound.PortalIdentityDirectory
import com.duluin.ftth.portal.application.port.outbound.PortalIdentityEntry
import com.duluin.ftth.portal.application.port.outbound.PortalIdentityValue
import com.duluin.ftth.portal.domain.model.PortalIdentityKind
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Baris indeks identitas portal — SENGAJA bukan tenant-aware (tanpa RLS), karena dibaca
 * sebelum tenant diketahui. Kolom `tenant_id` justru adalah JAWABAN yang dicari, bukan
 * penyaringnya.
 */
@Entity
@Table(name = "portal_identity")
class PortalIdentityJpaEntity(
    id: UUID,

    @Column(name = "tenant_id", nullable = false, updatable = false)
    var tenantId: UUID,

    @Column(name = "customer_id", nullable = false, updatable = false)
    var customerId: UUID,

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 16)
    var kind: PortalIdentityKind,

    @Column(name = "value", nullable = false, length = 255)
    var value: String,
) : BaseJpaEntity(id)

interface PortalIdentityJpaRepository : JpaRepository<PortalIdentityJpaEntity, UUID> {

    fun findByValueIn(values: Collection<String>): List<PortalIdentityJpaEntity>

    fun deleteByCustomerId(customerId: UUID)

    fun existsByTenantIdAndValue(tenantId: UUID, value: String): Boolean
}

@Component
class PortalIdentityDirectoryAdapter(
    private val jpa: PortalIdentityJpaRepository,
) : PortalIdentityDirectory {

    override fun findByValues(values: Collection<String>): List<PortalIdentityEntry> {
        if (values.isEmpty()) return emptyList()
        return jpa.findByValueIn(values)
            .map { PortalIdentityEntry(tenantId = it.tenantId, customerId = it.customerId) }
            // Satu pelanggan bisa cocok lewat dua jalur sekaligus (mis. ketikan angka yang
            // sah sebagai nomor HP DAN sebagai username-nya) — jangan diverifikasi dua kali.
            .distinct()
    }

    override fun replaceFor(tenantId: UUID, customerId: UUID, values: List<PortalIdentityValue>) {
        jpa.deleteByCustomerId(customerId)
        // Flush penghapusan dulu, kalau tidak nilai lama milik pelanggan ini sendiri masih
        // memegang unique constraint dan penulisan-ulang yang tak berubah pun akan gagal.
        jpa.flush()
        val fresh = values
            // Satu nilai bisa lahir dua kali dari pelanggan yang SAMA — username PPPoE yang
            // berupa deretan angka menghasilkan nilai identik dengan nomor HP-nya. Yang
            // pertama menang (LOGIN, sesuai urutan pemanggil), sisanya dibuang di sini
            // sebelum sempat menabrak unique constraint.
            .distinctBy { it.value }
            // Pemilik lama nilai yang sama — pelanggan LAIN di tenant ini, mis. satu keluarga
            // berbagi satu nomor — berhak mempertahankannya; yang datang belakangan mengalah
            // dan tetap bisa masuk lewat username-nya sendiri.
            .filterNot { jpa.existsByTenantIdAndValue(tenantId, it.value) }
            .map {
                PortalIdentityJpaEntity(
                    id = UuidV7.generate(),
                    tenantId = tenantId,
                    customerId = customerId,
                    kind = it.kind,
                    value = it.value,
                )
            }
        jpa.saveAll(fresh)
    }
}

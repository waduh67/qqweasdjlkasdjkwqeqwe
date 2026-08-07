package com.duluin.ftth.tenancy.application.service

import com.duluin.ftth.common.audit.AuditTrailEvent
import com.duluin.ftth.common.domain.Page
import com.duluin.ftth.common.domain.PageRequest
import com.duluin.ftth.common.domain.error.NotFoundException
import com.duluin.ftth.common.domain.error.ValidationException
import com.duluin.ftth.common.infrastructure.persistence.TenantEraser
import com.duluin.ftth.common.security.CurrentUserProvider
import com.duluin.ftth.tenancy.TenantApi
import com.duluin.ftth.tenancy.TenantRef
import com.duluin.ftth.tenancy.application.port.inbound.ManageTenantUseCase
import com.duluin.ftth.tenancy.application.port.outbound.TenantRepository
import com.duluin.ftth.tenancy.domain.model.Tenant
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Mengimplementasikan API lintas-module [TenantApi] sekaligus use case web
 * [ManageTenantUseCase]. Semua mutasi berada dalam transaksi.
 */
@Service
@Transactional
class TenantService(
    private val tenantRepository: TenantRepository,
    private val events: ApplicationEventPublisher,
    private val currentUser: CurrentUserProvider,
    private val tenantEraser: TenantEraser,
) : TenantApi, ManageTenantUseCase {

    companion object {
        const val PLATFORM_SLUG = "platform"
    }

    @Transactional(readOnly = true)
    override fun findById(id: UUID): TenantRef? = tenantRepository.findById(id)?.toRef()

    @Transactional(readOnly = true)
    override fun findBySlug(slug: String): TenantRef? =
        tenantRepository.findBySlug(slug.trim().lowercase())?.toRef()

    @Transactional(readOnly = true)
    override fun requireById(id: UUID): TenantRef =
        findById(id) ?: throw NotFoundException("Tenant $id tidak ditemukan")

    @Transactional(readOnly = true)
    override fun platformTenantId(): UUID =
        tenantRepository.findBySlug(PLATFORM_SLUG)?.id
            ?: throw NotFoundException("Tenant platform belum diinisialisasi")

    @Transactional(readOnly = true)
    override fun findActiveTenantIds(): List<UUID> = tenantRepository.findActiveIds()

    override fun ensureTenant(slug: String, name: String): TenantRef {
        val normalized = slug.trim().lowercase()
        tenantRepository.findBySlug(normalized)?.let { return it.toRef() }

        val created = tenantRepository.save(Tenant.create(normalized, name))
        events.publishEvent(
            AuditTrailEvent(
                tenantId = created.id,
                actorId = currentUser.currentOrNull()?.userId,
                actorEmail = currentUser.currentOrNull()?.email,
                action = "tenant.created",
                entityType = "Tenant",
                entityId = created.id.toString(),
                detail = mapOf("slug" to created.slug, "name" to created.name),
            ),
        )
        return created.toRef()
    }

    @Transactional(readOnly = true)
    override fun list(pageRequest: PageRequest): Page<TenantRef> =
        tenantRepository.findAll(pageRequest).map(Tenant::toRef)

    @Transactional(readOnly = true)
    override fun get(id: UUID): TenantRef = requireById(id)

    override fun suspend(id: UUID): TenantRef =
        mutate(id) { it.suspend() }

    override fun activate(id: UUID): TenantRef =
        mutate(id) { it.activate() }

    /**
     * Hapus PERMANEN tenant beserta seluruh datanya lintas module. `NOT_SUPPORTED` agar
     * tak ada transaksi luar berkonteks platform-admin yang menahan koneksi — penghapusan
     * destruktif dipegang [TenantEraser] dalam transaksinya sendiri yang terikat tenant
     * target (lihat gotcha RLS di sana). Audit dicatat di bawah tenant `platform` karena
     * `audit_log` milik tenant ini ikut terhapus dan aktornya adalah platform admin.
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    override fun delete(id: UUID) {
        val tenant = tenantRepository.findById(id) ?: throw NotFoundException("Tenant $id tidak ditemukan")
        if (tenant.slug == PLATFORM_SLUG) throw ValidationException("Tenant platform tidak bisa dihapus")

        tenantEraser.erase(id)

        events.publishEvent(
            AuditTrailEvent(
                tenantId = platformTenantId(),
                actorId = currentUser.currentOrNull()?.userId,
                actorEmail = currentUser.currentOrNull()?.email,
                action = "tenant.deleted",
                entityType = "Tenant",
                entityId = id.toString(),
                detail = mapOf("slug" to tenant.slug, "name" to tenant.name),
            ),
        )
    }

    private fun mutate(id: UUID, change: (Tenant) -> Unit): TenantRef {
        val tenant = tenantRepository.findById(id) ?: throw NotFoundException("Tenant $id tidak ditemukan")
        change(tenant)
        return tenantRepository.save(tenant).toRef()
    }
}

private fun Tenant.toRef() = TenantRef(id = id, slug = slug, name = name, status = status)

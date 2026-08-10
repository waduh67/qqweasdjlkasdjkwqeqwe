package com.duluin.ftth.tenancy.application.service

import com.duluin.ftth.common.audit.AuditTrailEvent
import com.duluin.ftth.common.domain.error.NotFoundException
import com.duluin.ftth.common.security.CurrentUserProvider
import com.duluin.ftth.tenancy.application.port.inbound.ExportTenantDataUseCase
import com.duluin.ftth.tenancy.application.port.outbound.TenantDataArchivePort
import com.duluin.ftth.tenancy.application.port.outbound.TenantExportReport
import com.duluin.ftth.tenancy.application.port.outbound.TenantRepository
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.io.OutputStream
import java.time.LocalDate

/**
 * Menyusun ekspor data tenant sendiri: menentukan nama arsip, mencatat jejak audit, lalu
 * menyerahkan penulisan isinya ke [TenantDataArchivePort].
 */
@Service
class TenantDataExportService(
    private val archive: TenantDataArchivePort,
    private val tenantRepository: TenantRepository,
    private val currentUser: CurrentUserProvider,
    private val events: ApplicationEventPublisher,
) : ExportTenantDataUseCase {

    @Transactional(readOnly = true)
    override fun archiveName(): String {
        val tenantId = currentUser.current().tenantId
        val tenant = tenantRepository.findById(tenantId)
            ?: throw NotFoundException("Tenant $tenantId tidak ditemukan")
        return "netops-${tenant.slug}-${LocalDate.now()}.zip"
    }

    /**
     * `NOT_SUPPORTED` agar tak ada transaksi luar yang menahan koneksi selama arsip mengalir:
     * pembacaannya dipegang adapter dalam transaksi read-only-nya sendiri yang terikat tenant
     * target (lihat gotcha RLS di sana).
     *
     * Audit sengaja dicatat SEBELUM satu bita pun terkirim. Seseorang yang mengunduh seluruh
     * basis data pelanggan lalu memutus koneksi di tengah jalan tetap sudah memegang datanya —
     * kalau jejaknya baru ditulis setelah unduhan tuntas, justru pengambilan yang paling patut
     * dicurigai yang tak meninggalkan jejak.
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    override fun exportCurrentTenant(target: OutputStream): TenantExportReport {
        val user = currentUser.current()
        events.publishEvent(
            AuditTrailEvent(
                tenantId = user.tenantId,
                actorId = user.userId,
                actorEmail = user.email,
                action = "tenant.data.exported",
                entityType = "Tenant",
                entityId = user.tenantId.toString(),
                detail = mapOf("catatan" to "seluruh data tenant diunduh sebagai arsip"),
            ),
        )
        return archive.writeArchive(user.tenantId, target)
    }
}

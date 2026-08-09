package com.duluin.ftth.portal.application.service

import com.duluin.ftth.portal.application.port.outbound.PortalIdentityDirectory
import com.duluin.ftth.portal.domain.model.PortalIdentifier
import com.duluin.ftth.tenancy.TenantApi
import com.duluin.ftth.tenancy.TenantRef
import com.duluin.ftth.tenancy.TenantStatus
import org.springframework.stereotype.Component
import java.util.UUID

/** Satu pelanggan (di satu ISP) yang mungkin dimaksud oleh identitas yang diketik. */
data class PortalCandidate(val tenant: TenantRef, val customerId: UUID)

/**
 * Menerjemahkan satu ketikan pelanggan menjadi daftar pelanggan yang mungkin dimaksud,
 * lintas ISP.
 *
 * Dipakai dua alur yang sama-sama pra-autentikasi — masuk dan pemulihan password — dan
 * sengaja dibagi supaya keduanya melihat kandidat yang PERSIS sama. Kalau pernah berbeda,
 * akan ada pelanggan yang bisa memulihkan password tapi tak bisa masuk (atau sebaliknya),
 * dan gejalanya nyaris mustahil dilacak dari laporan pengguna.
 */
@Component
class PortalIdentityResolver(
    private val identityDirectory: PortalIdentityDirectory,
    private val tenantApi: TenantApi,
) {
    /**
     * @param requestedTenant bila bukan null, kandidat disaring ke ISP itu saja.
     * @param limit batas atas jumlah kandidat. Setiap kandidat berujung pada kerja mahal
     *   (verifikasi BCrypt atau pengiriman pesan), jadi satu identitas populer tak boleh bisa
     *   dipakai membebani server maupun membanjiri kotak masuk orang.
     */
    fun resolve(identifier: String, requestedTenant: TenantRef?, limit: Int): List<PortalCandidate> {
        val values = PortalIdentifier.candidates(identifier)
        if (values.isEmpty()) return emptyList()
        return identityDirectory.findByValues(values).asSequence()
            .filter { requestedTenant == null || it.tenantId == requestedTenant.id }
            .mapNotNull { entry ->
                // Tenant tersuspend tak bisa dimasuki maupun dipulihkan — layanannya memang mati.
                val tenant = requestedTenant ?: tenantApi.findById(entry.tenantId)
                tenant?.takeIf { it.status == TenantStatus.ACTIVE }
                    ?.let { PortalCandidate(it, entry.customerId) }
            }
            .distinctBy { it.tenant.id to it.customerId }
            .take(limit)
            .toList()
    }
}

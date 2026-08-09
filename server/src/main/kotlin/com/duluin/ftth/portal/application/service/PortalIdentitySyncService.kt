package com.duluin.ftth.portal.application.service

import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.customer.CustomerApi
import com.duluin.ftth.portal.application.port.outbound.PortalCredentialRepository
import com.duluin.ftth.portal.application.port.outbound.PortalIdentityDirectory
import com.duluin.ftth.portal.application.port.outbound.PortalIdentityValue
import com.duluin.ftth.portal.domain.model.PortalIdentifier
import com.duluin.ftth.portal.domain.model.PortalIdentityKind
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Menjaga indeks identitas portal tetap mencerminkan kenyataan: username kredensial plus
 * email & nomor HP pelanggan.
 *
 * Selalu menulis ULANG seluruh baris milik satu pelanggan, tak pernah menambal. Yang penting
 * di sini bukan cuma "identitas baru bisa dipakai masuk", tapi juga "identitas lama BERHENTI
 * bisa" — dan hanya penulisan-ulang yang menjamin keduanya sekaligus. Nomor HP yang salah
 * ketik lalu dikoreksi operator tak boleh tetap membuka pintu akun pelanggan.
 *
 * Pelanggan tanpa kredensial portal sengaja tak diindeks sama sekali: tanpa password, baris
 * indeksnya hanya penunjuk ke akun yang mustahil dimasuki.
 *
 * Dipanggil di dalam tenant context yang sudah terpasang (RLS untuk membaca kredensial &
 * pelanggan); indeksnya sendiri tak ber-RLS, jadi tenant-nya disebut eksplisit saat menulis.
 */
@Service
class PortalIdentitySyncService(
    private val directory: PortalIdentityDirectory,
    private val credentials: PortalCredentialRepository,
    private val customerApi: CustomerApi,
) {
    @Transactional
    fun sync(customerId: UUID) {
        val tenantId = TenantContext.tenantId()
        val credential = credentials.findByCustomerId(customerId)
        if (credential == null) {
            directory.replaceFor(tenantId, customerId, emptyList())
            return
        }
        val customer = customerApi.findCustomer(customerId)
        // Urutan menentukan siapa yang menang saat bentrok dengan pelanggan lain di tenant
        // yang sama: username lebih dulu karena itu identitas yang sengaja diberikan operator,
        // sedangkan kontak bisa saja tertukar/dipakai bersama satu keluarga. Urutan yang sama
        // dipakai backfill di V79 — jangan diubah sebelah saja.
        val values = buildList {
            PortalIdentifier.login(credential.login)?.let { add(PortalIdentityValue(PortalIdentityKind.LOGIN, it)) }
            PortalIdentifier.email(customer?.email)?.let { add(PortalIdentityValue(PortalIdentityKind.EMAIL, it)) }
            PortalIdentifier.phone(customer?.phone)?.let { add(PortalIdentityValue(PortalIdentityKind.PHONE, it)) }
        }
        directory.replaceFor(tenantId, customerId, values)
    }
}

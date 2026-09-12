package com.duluin.ftth.order.application.service

import com.duluin.ftth.common.domain.error.NotFoundException
import com.duluin.ftth.common.domain.error.ValidationException
import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.order.application.port.inbound.PublicOrderIntake
import com.duluin.ftth.order.application.port.inbound.PublicOrderReceipt
import com.duluin.ftth.order.application.port.inbound.PublicOrderSubmission
import com.duluin.ftth.order.application.port.inbound.PublicOrderTrackView
import com.duluin.ftth.order.application.port.inbound.PublicOrderUseCase
import com.duluin.ftth.order.application.port.inbound.PublicPlanView
import com.duluin.ftth.order.application.port.outbound.PublicRequestThrottle
import com.duluin.ftth.order.config.PublicOrderProperties
import com.duluin.ftth.tenancy.TenantApi
import com.duluin.ftth.tenancy.TenantRef
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * Pintu pemesanan publik: rem laju → resolusi slug → pasang tenant → kerja.
 *
 * SENGAJA TIDAK `@Transactional` — persis alasan `PublicInvoicePaymentService`. Tenant
 * di-resolve dari slug LEBIH DULU dan [TenantContext] dipasang sebelum pekerja transaksional
 * dipanggil, supaya session Hibernate terbuka dengan GUC `app.tenant_id` yang benar. Kalau
 * kelas ini transaksional, koneksinya sudah diambil dengan sentinel ROOT dan setiap tabel
 * ber-RLS FORCE memulangkan NOL BARIS tanpa satu pun error — pesanan pengunjung gagal dengan
 * pesan yang menyesatkan dan jejaknya sulit dilacak.
 *
 * Status tenant TIDAK ikut menyaring. Tenant yang langganan SaaS-nya tertunggak tetap boleh
 * menerima pesanan baru: calon pelanggannya tak ada urusan dengan tagihan penyedianya, dan
 * menolak mereka justru memperparah keadaan tenant yang sedang kesulitan.
 */
@Service
class PublicOrderService(
    private val tenants: TenantApi,
    private val intake: PublicOrderIntake,
    private val throttle: PublicRequestThrottle,
    private val props: PublicOrderProperties,
) : PublicOrderUseCase {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun plans(tenantSlug: String, clientIp: String?): List<PublicPlanView> {
        // Daftar paket ikut rem "track": ia sama-sama bacaan murah yang wajar diulang
        // pengunjung, dan menaruhnya di rem "submit" akan menghabiskan jatah memesan
        // hanya karena orangnya membuka-tutup formulir.
        spend(SCOPE_TRACK_IP, clientIp.orAnonymous(), props.trackPerIp, TOO_MANY_READS)
        val tenant = requireTenant(tenantSlug)
        return TenantContext.runAs(tenant.id) { intake.plans(tenant.id) }
    }

    override fun submit(tenantSlug: String, submission: PublicOrderSubmission, clientIp: String?): PublicOrderReceipt {
        /*
         * Honeypot diperiksa PALING AWAL, sebelum rem dan sebelum slug disentuh: bot yang
         * mengisi seluruh kolom tak perlu diberi tahu apa pun tentang tenant kita, dan tak
         * perlu pula menghabiskan satu pun query.
         */
        if (!submission.honeypot.isNullOrBlank()) {
            log.info("Pengiriman pesanan publik ditolak honeypot untuk slug '{}'", tenantSlug.take(MAX_LOG_SLUG))
            // Sengaja kalimat validasi biasa: pengisinya tak boleh tahu kolom mana yang menjebaknya.
            throw ValidationException("Formulir tidak valid")
        }
        /*
         * Rem per-IP dulu (menahan satu skrip), lalu rem per-tenant SETELAH slug diketahui
         * (menahan kolam IP yang tak pernah menyentuh batas per-IP mana pun). Keduanya
         * diambil meski permintaannya nanti gagal validasi — lihat catatan di
         * `PublicRequestThrottle.spend`.
         */
        spend(SCOPE_SUBMIT_IP, clientIp.orAnonymous(), props.submitPerIp, TOO_MANY_ORDERS)
        val tenant = requireTenant(tenantSlug)
        spend(SCOPE_SUBMIT_TENANT, tenant.id.toString(), props.submitPerTenant, TOO_MANY_ORDERS)
        return TenantContext.runAs(tenant.id) { intake.submit(tenant.id, tenant.name, submission) }
    }

    override fun track(tenantSlug: String, orderNumber: String, phone: String, clientIp: String?): PublicOrderTrackView {
        spend(SCOPE_TRACK_IP, clientIp.orAnonymous(), props.trackPerIp, TOO_MANY_READS)
        val tenant = requireTenant(tenantSlug)
        return TenantContext.runAs(tenant.id) { intake.track(tenant.id, orderNumber, phone) }
    }

    private fun spend(scope: String, subject: String, quota: PublicOrderProperties.Quota, message: String) =
        throttle.spend(scope, subject, quota.limit, quota.window, message)

    /**
     * Slug yang tak dikenal dijawab dengan kalimat yang SAMA seperti pesanan yang tak ditemukan.
     * Membedakannya berarti memberi alat pemetaan: siapa pun bisa memanen daftar slug tenant
     * kita sekadar dengan menebak-nebak.
     */
    private fun requireTenant(slug: String): TenantRef =
        tenants.findBySlug(slug.trim().lowercase()) ?: throw NotFoundException(NOT_FOUND)

    /**
     * `remoteAddr` bisa null di beberapa container uji. Semua permintaan tanpa IP dikumpulkan
     * ke satu ember bernama: lebih baik mereka saling berebut jatah daripada seluruhnya lolos
     * tanpa hitungan.
     */
    private fun String?.orAnonymous() = this?.takeIf { it.isNotBlank() } ?: "unknown"

    private companion object {
        const val SCOPE_SUBMIT_IP = "public-order-submit-ip"
        const val SCOPE_SUBMIT_TENANT = "public-order-submit-tenant"
        const val SCOPE_TRACK_IP = "public-order-track-ip"
        const val TOO_MANY_ORDERS = "Terlalu banyak pesanan dikirim dari jaringan ini"
        const val TOO_MANY_READS = "Terlalu banyak permintaan dari jaringan ini"
        const val NOT_FOUND = "Pesanan tidak ditemukan. Periksa kembali nomor pesanan dan nomor HP Anda."
        const val MAX_LOG_SLUG = 64
    }
}

package com.duluin.ftth.order.application.service

import com.duluin.ftth.catalog.CatalogApi
import com.duluin.ftth.common.domain.error.NotFoundException
import com.duluin.ftth.common.domain.error.ValidationException
import com.duluin.ftth.customer.CustomerApi
import com.duluin.ftth.order.CreateOrderCommand
import com.duluin.ftth.order.OperationCommand
import com.duluin.ftth.order.OrderLineCommand
import com.duluin.ftth.order.OrderTransition
import com.duluin.ftth.order.OrderTransitionCommand
import com.duluin.ftth.order.OrderView
import com.duluin.ftth.order.PortalOrderStatus
import com.duluin.ftth.order.ServiceAddress
import com.duluin.ftth.order.application.port.inbound.PublicOrderIntake
import com.duluin.ftth.order.application.port.inbound.PublicOrderReceipt
import com.duluin.ftth.order.application.port.inbound.PublicOrderSubmission
import com.duluin.ftth.order.application.port.inbound.PublicOrderTrackEntry
import com.duluin.ftth.order.application.port.inbound.PublicOrderTrackView
import com.duluin.ftth.order.application.port.inbound.PublicPlanView
import com.duluin.ftth.order.application.port.inbound.SystemOrderUseCase
import com.duluin.ftth.order.application.port.outbound.OrderAuditStore
import com.duluin.ftth.order.application.port.outbound.OrderLeadRepository
import com.duluin.ftth.order.application.port.outbound.OrderRepository
import com.duluin.ftth.order.application.port.outbound.OrderSearchPort
import com.duluin.ftth.order.application.port.outbound.OrderTrackRow
import com.duluin.ftth.order.domain.model.LeadSource
import com.duluin.ftth.order.domain.model.OrderLead
import com.duluin.ftth.order.domain.model.OrderPortalFlag
import com.duluin.ftth.order.domain.model.OrderStatus
import com.duluin.ftth.order.domain.model.portalStatusOf
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

/**
 * Isi transaksional pintu pemesanan publik. Tenant sudah dipasang pemanggilnya
 * ([PublicOrderService]); di sini tinggal bekerja.
 *
 * Seluruh kegagalan pencarian dipulangkan dengan SATU kalimat lewat [notFound] — pembeda
 * sekecil apa pun antara "slug tak ada", "nomor tak ada", dan "HP tak cocok" langsung menjadi
 * alat pemetaan bagi orang yang menebak-nebak.
 */
@Suppress("LongParameterList")
@Service
class PublicOrderIntakeService(
    private val system: SystemOrderUseCase,
    private val orders: OrderRepository,
    private val leads: OrderLeadRepository,
    private val catalog: CatalogApi,
    private val customers: CustomerApi,
    private val search: OrderSearchPort,
    private val audit: OrderAuditStore,
) : PublicOrderIntake {

    @Transactional(readOnly = true)
    override fun plans(tenantId: UUID): List<PublicPlanView> =
        catalog.findActivePlans().map { PublicPlanView(it.planId, it.packageName, it.monthlyFee, it.bandwidthMbps) }

    @Transactional
    override fun submit(tenantId: UUID, tenantName: String, submission: PublicOrderSubmission): PublicOrderReceipt {
        val plan = catalog.findPlanCommercial(submission.planId)?.takeIf { it.active }
            ?: throw ValidationException("Paket yang dipilih tidak tersedia")

        val requestId = submission.requestId?.trim()?.ifBlank { null } ?: UUID.randomUUID().toString()
        val hash = canonicalHash(tenantId, submission)
        val submitOperation = OperationCommand(NAMESPACE, "$requestId:submit", hash)

        /*
         * Dicegat SEBELUM prospek dibuat, bukan hanya diandalkan pada idempotensi di dalam
         * `SystemOrderUseCase.create`. Pembuatan `OrderLead` TIDAK berada di bawah penjagaan
         * operation key mana pun: pengunjung yang menekan "Kirim" dua kali akan meninggalkan
         * prospek yatim kedua di antrean operator meski pesanannya hanya satu.
         */
        val replayed = replayedOrder(tenantId, submitOperation)
        if (replayed != null) return replayed.toReceipt(tenantId, tenantName)

        val now = Instant.now()
        val lead = OrderLead.create(
            tenantId = tenantId,
            name = submission.name,
            phone = submission.phone,
            email = submission.email,
            address = fullAddress(submission),
            latitude = submission.latitude,
            longitude = submission.longitude,
            interestedPlanId = plan.planId,
            source = LeadSource.PUBLIC_WEB,
            notes = submission.notes,
            now = now,
        )
        leads.save(lead)

        val created = system.create(
            tenantId = tenantId,
            // Tak ada pelaku internal: memang bukan operator yang menekan tombolnya.
            actorId = null,
            command = CreateOrderCommand(
                customerId = null,
                leadId = lead.id,
                // Satu baris = paket yang dipilih. Perangkat dan biaya pemasangan ditambahkan
                // operator saat meninjau; pengunjung tak pernah memilih barang gudang sendiri.
                lines = listOf(OrderLineCommand(plan.planId, plan.packageName, 1)),
                serviceAddress = ServiceAddress(
                    address = submission.address.trim(),
                    city = submission.city.trim(),
                    postalCode = submission.postalCode.trim(),
                    latitude = submission.latitude,
                    longitude = submission.longitude,
                ),
                operation = OperationCommand(NAMESPACE, "$requestId:create", hash),
            ),
        )

        /*
         * Langsung di-SUBMIT, tidak ditinggal DRAFT. DRAFT tak punya padanan status portal
         * (lihat `portalStatusOf`), jadi pesanan yang berhenti di sana tak akan pernah bisa
         * dilacak pemesannya — ia menerima nomor pesanan yang selamanya menjawab "tidak
         * ditemukan".
         */
        val submitted = system.transition(
            tenantId = tenantId,
            actorId = null,
            command = OrderTransitionCommand(
                orderId = created.id,
                transition = OrderTransition.SUBMIT,
                expectedRevision = created.revision,
                operation = submitOperation,
            ),
        )
        return submitted.toReceipt(tenantId, tenantName, submittedAt = now)
    }

    @Transactional(readOnly = true)
    override fun track(tenantId: UUID, orderNumber: String, phone: String): PublicOrderTrackView {
        val row = search.findByNumber(tenantId, orderNumber.trim().uppercase()) ?: throw notFound()
        if (!row.belongsTo(phone)) throw notFound()
        val flag = row.portalFlag?.let { OrderPortalFlag.valueOf(it) }
        // DRAFT memulangkan null: pesanan yang belum diajukan tak boleh terlihat pengunjung.
        val status = portalStatusOf(row.status, flag) ?: throw notFound()
        return PublicOrderTrackView(
            orderNumber = row.orderNumber,
            status = status,
            statusNote = row.portalFlagReason,
            appointmentStartsAt = row.appointmentStartsAt,
            appointmentEndsAt = row.appointmentEndsAt,
            submittedAt = row.createdAt,
            updatedAt = row.updatedAt,
            timeline = timelineOf(tenantId, row.id),
        )
    }

    /**
     * Riwayat versi pengunjung: HANYA status dan kapan. Alasan pembatalan, pelaku, dan kunci
     * operasi yang ada di `order_audit` tak pernah ikut — itu catatan internal.
     *
     * Langkah berurutan yang status portalnya sama dilipat jadi satu. Satu perubahan internal
     * (mis. pemberian penanda, atau ACCEPT yang langsung disusul SCHEDULE) tak boleh tampak
     * sebagai dua langkah berbeda di mata pemesan — itu membingungkan tanpa menambah informasi.
     */
    private fun timelineOf(tenantId: UUID, orderId: UUID): List<PublicOrderTrackEntry> {
        val entries = mutableListOf<PublicOrderTrackEntry>()
        audit.timeline(tenantId, orderId).forEach { entry ->
            val status = runCatching { OrderStatus.valueOf(entry.toStatus) }.getOrNull()
                ?.let { portalStatusOf(it, flag = null) } ?: return@forEach
            if (entries.lastOrNull()?.status == status) return@forEach
            entries += PublicOrderTrackEntry(status, entry.occurredAt)
        }
        return entries
    }

    private fun replayedOrder(tenantId: UUID, operation: OperationCommand): OrderView? =
        orders.findOutcome(tenantId, operation.namespace, operation.key)?.value

    private fun OrderView.toReceipt(tenantId: UUID, tenantName: String, submittedAt: Instant? = null) =
        PublicOrderReceipt(
            orderNumber = orderNumber ?: throw notFound(),
            status = portalStatusOf(OrderStatus.valueOf(status), flag = null) ?: PortalOrderStatus.RECEIVED,
            tenantName = tenantName,
            submittedAt = submittedAt
                ?: orderNumber?.let { search.findByNumber(tenantId, it)?.createdAt }
                ?: Instant.now(),
        )

    /**
     * Nomor HP dibandingkan lewat DIGIT-nya saja, dan hanya 9 digit terakhir. "+6281234567890",
     * "081234567890", dan "0812-3456-7890" adalah orang yang sama, dan pemesan tak akan ingat
     * bentuk persis yang dulu ia ketik. Konsekuensinya diterima sadar: pencocokan ini sedikit
     * lebih longgar daripada kesamaan persis — tapi penebak tetap harus menebak NOMOR PESANAN
     * yang benar lebih dulu, dan itulah penjaga yang sesungguhnya.
     */
    private fun OrderTrackRow.belongsTo(phone: String): Boolean {
        val supplied = phone.digitsOnly()
        if (supplied.length < MIN_PHONE_DIGITS) return false
        val owner = leadPhone ?: customerId?.let { customers.findCustomer(it)?.phone } ?: return false
        val actual = owner.digitsOnly()
        if (actual.length < MIN_PHONE_DIGITS) return false
        return supplied.takeLast(PHONE_MATCH_DIGITS) == actual.takeLast(PHONE_MATCH_DIGITS)
    }

    private fun String.digitsOnly() = filter { it.isDigit() }

    private fun fullAddress(submission: PublicOrderSubmission) =
        listOf(submission.address.trim(), submission.city.trim(), submission.postalCode.trim())
            .filter { it.isNotBlank() }
            .joinToString(", ")

    /**
     * Sidik jari isi formulir. Dipakai sebagai `payloadHash` operation key: pengiriman ulang
     * dengan `requestId` sama tapi isi BERBEDA ditolak sebagai konflik, bukan diam-diam
     * memulangkan pesanan lama seolah data barunya tercatat.
     */
    private fun canonicalHash(tenantId: UUID, submission: PublicOrderSubmission): String {
        val canonical = listOf(
            tenantId, submission.planId, submission.name.trim(), submission.phone.trim(),
            submission.email?.trim(), submission.address.trim(), submission.city.trim(),
            submission.postalCode.trim(), submission.latitude, submission.longitude,
        ).joinToString("")
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private fun notFound() = NotFoundException("Pesanan tidak ditemukan. Periksa kembali nomor pesanan dan nomor HP Anda.")

    private companion object {
        const val NAMESPACE = "public-order"
        const val MIN_PHONE_DIGITS = 8
        const val PHONE_MATCH_DIGITS = 9
    }
}

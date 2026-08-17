package com.duluin.ftth.customer.application.service

import com.duluin.ftth.catalog.CatalogApi
import com.duluin.ftth.common.domain.error.NotFoundException
import com.duluin.ftth.common.domain.error.ValidationException
import com.duluin.ftth.common.infrastructure.audit.AuditRecorder
import com.duluin.ftth.customer.SubscriptionActivated
import com.duluin.ftth.customer.SubscriptionIsolated
import com.duluin.ftth.customer.SubscriptionPlanChanged
import com.duluin.ftth.customer.SubscriptionTerminated
import com.duluin.ftth.customer.application.port.inbound.ManageSubscriptionUseCase
import com.duluin.ftth.customer.application.port.inbound.SaveSubscriptionCommand
import com.duluin.ftth.customer.application.port.inbound.SubscriptionView
import com.duluin.ftth.customer.application.port.outbound.CustomerRepository
import com.duluin.ftth.customer.application.port.outbound.SubscriptionRepository
import com.duluin.ftth.customer.domain.model.PlanSnapshot
import com.duluin.ftth.customer.domain.model.Subscription
import com.duluin.ftth.customer.domain.model.SubscriptionStatus
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

@Service
@Transactional
class SubscriptionService(
    private val subscriptionRepository: SubscriptionRepository,
    private val customerRepository: CustomerRepository,
    private val catalog: CatalogApi,
    private val auditor: AuditRecorder,
    private val events: ApplicationEventPublisher,
) : ManageSubscriptionUseCase {

    @Transactional(readOnly = true)
    override fun findForCustomer(customerId: UUID): SubscriptionView? =
        subscriptionRepository.findByCustomerId(customerId)?.toView()

    /**
     * Menetapkan paket pelanggan: buka bila belum ada, hidupkan lagi bila sudah berakhir,
     * ganti di tempat bila sedang berjalan.
     *
     * Tiga cabang, satu gerakan operator. Yang dulu terpisah jadi "tambah langganan" dan
     * "sunting langganan" adalah sumber kerusakan yang mahal: operator meng-upgrade paket
     * dengan menambah langganan baru, yang lama tetap hidup, dan pelanggan tertagih dua kali
     * sampai ada yang sadar — sementara sisi jaringan tak pernah tahu paketnya berpindah
     * karena yang terbit hanya "langganan baru", bukan "paket berubah". Sekarang tak ada
     * pintu untuk membuat yang kedua; kuncinya di V107.
     *
     * [SubscriptionPlanChanged] hanya terbit bila paketnya BENAR-BENAR berpindah — menekan
     * simpan dengan pilihan yang sama tak boleh mengantre pekerjaan RADIUS.
     */
    override fun setPlan(customerId: UUID, command: SaveSubscriptionCommand): SubscriptionView {
        val customer = customerRepository.findById(customerId)
            ?: throw NotFoundException("Pelanggan $customerId tidak ditemukan")
        val snapshot = resolveSnapshot(command)
        val existing = subscriptionRepository.findByCustomerId(customerId)
        if (existing == null) {
            val opened = subscriptionRepository.save(Subscription.create(customer.tenantId, customerId, snapshot))
            auditor.record(
                "subscription.created", "Subscription", opened.id, opened.tenantId,
                mapOf("customer" to customer.code, "package" to opened.packageName),
            )
            return opened.toView()
        }

        val previousPlanId = existing.planId
        val action = if (existing.status == SubscriptionStatus.TERMINATED) {
            existing.resubscribe(snapshot)
            "subscription.resubscribed"
        } else {
            existing.updatePackage(snapshot)
            "subscription.updated"
        }
        val view = saveAndAudit(existing, action)
        if (existing.planId != previousPlanId) {
            events.publishEvent(
                SubscriptionPlanChanged(existing.tenantId, existing.id, existing.customerId, existing.planId),
            )
        }
        return view
    }

    /**
     * Menyalin sisi komersial paket katalog menjadi snapshot langganan (customer →
     * catalog, acyclic). Paket nonaktif ditolak agar tak dipasang ke pelanggan baru.
     */
    private fun resolveSnapshot(command: SaveSubscriptionCommand): PlanSnapshot {
        val plan = catalog.findPlanCommercial(command.planId)
            ?: throw NotFoundException("Paket ${command.planId} tidak ditemukan")
        if (!plan.active) throw ValidationException("Paket ${plan.packageName} nonaktif, tak bisa dipilih")
        return PlanSnapshot(
            planId = plan.planId,
            packageName = plan.packageName,
            bandwidthMbps = plan.bandwidthMbps,
            monthlyFee = command.monthlyFeeOverride ?: plan.monthlyFee,
            prorateOnActivation = plan.prorateOnActivation,
            billingDayOfMonth = plan.billingDayOfMonth,
            graceDays = plan.graceDays,
            autoIsolir = plan.autoIsolir,
        )
    }

    override fun activate(id: UUID): SubscriptionView {
        val subscription = require(id)
        subscription.activate()
        val view = saveAndAudit(subscription, "subscription.activated")
        events.publishEvent(SubscriptionActivated(subscription.tenantId, subscription.id, subscription.customerId))
        return view
    }

    override fun activateImported(id: UUID, activatedAt: Instant?, billingDayOfMonth: Int?): SubscriptionView {
        val subscription = require(id)
        subscription.activate(activatedAt ?: Instant.now())
        if (billingDayOfMonth != null) subscription.overrideBillingDay(billingDayOfMonth)
        val view = saveAndAudit(subscription, "subscription.activated")
        events.publishEvent(SubscriptionActivated(subscription.tenantId, subscription.id, subscription.customerId))
        return view
    }

    override fun overrideBilling(id: UUID, billingDayOfMonth: Int?): SubscriptionView {
        val subscription = require(id)
        subscription.overrideBillingDay(billingDayOfMonth)
        return saveAndAudit(subscription, "subscription.updated")
    }

    override fun isolate(id: UUID): SubscriptionView {
        val subscription = require(id)
        subscription.isolate()
        val view = saveAndAudit(subscription, "subscription.isolated")
        events.publishEvent(SubscriptionIsolated(subscription.tenantId, subscription.id, subscription.customerId))
        return view
    }

    override fun terminate(id: UUID): SubscriptionView {
        val subscription = require(id)
        subscription.terminate()
        val view = saveAndAudit(subscription, "subscription.terminated")
        events.publishEvent(SubscriptionTerminated(subscription.tenantId, subscription.id, subscription.customerId))
        return view
    }

    private fun saveAndAudit(subscription: Subscription, action: String): SubscriptionView {
        val saved = subscriptionRepository.save(subscription)
        auditor.record(
            action, "Subscription", saved.id, saved.tenantId,
            mapOf("package" to saved.packageName, "status" to saved.status.name),
        )
        return saved.toView()
    }

    private fun require(id: UUID): Subscription =
        subscriptionRepository.findById(id) ?: throw NotFoundException("Langganan $id tidak ditemukan")
}

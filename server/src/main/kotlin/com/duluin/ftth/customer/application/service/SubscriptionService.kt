package com.duluin.ftth.customer.application.service

import com.duluin.ftth.common.domain.error.NotFoundException
import com.duluin.ftth.common.infrastructure.audit.AuditRecorder
import com.duluin.ftth.customer.SubscriptionActivated
import com.duluin.ftth.customer.SubscriptionIsolated
import com.duluin.ftth.customer.SubscriptionTerminated
import com.duluin.ftth.customer.application.port.inbound.ManageSubscriptionUseCase
import com.duluin.ftth.customer.application.port.inbound.SaveSubscriptionCommand
import com.duluin.ftth.customer.application.port.inbound.SubscriptionView
import com.duluin.ftth.customer.application.port.outbound.CustomerRepository
import com.duluin.ftth.customer.application.port.outbound.SubscriptionRepository
import com.duluin.ftth.customer.domain.model.Subscription
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
@Transactional
class SubscriptionService(
    private val subscriptionRepository: SubscriptionRepository,
    private val customerRepository: CustomerRepository,
    private val auditor: AuditRecorder,
    private val events: ApplicationEventPublisher,
) : ManageSubscriptionUseCase {

    @Transactional(readOnly = true)
    override fun listForCustomer(customerId: UUID): List<SubscriptionView> =
        subscriptionRepository.findByCustomerId(customerId).map { it.toView() }

    override fun create(customerId: UUID, command: SaveSubscriptionCommand): SubscriptionView {
        val customer = customerRepository.findById(customerId)
            ?: throw NotFoundException("Pelanggan $customerId tidak ditemukan")
        val subscription = subscriptionRepository.save(
            Subscription.create(
                tenantId = customer.tenantId,
                customerId = customerId,
                packageName = command.packageName,
                bandwidthMbps = command.bandwidthMbps,
                monthlyFee = command.monthlyFee,
            ),
        )
        auditor.record(
            "subscription.created", "Subscription", subscription.id, subscription.tenantId,
            mapOf("customer" to customer.code, "package" to subscription.packageName),
        )
        return subscription.toView()
    }

    override fun update(id: UUID, command: SaveSubscriptionCommand): SubscriptionView {
        val subscription = require(id)
        subscription.updatePackage(command.packageName, command.bandwidthMbps, command.monthlyFee)
        return saveAndAudit(subscription, "subscription.updated")
    }

    override fun activate(id: UUID): SubscriptionView {
        val subscription = require(id)
        subscription.activate()
        val view = saveAndAudit(subscription, "subscription.activated")
        events.publishEvent(SubscriptionActivated(subscription.tenantId, subscription.id, subscription.customerId))
        return view
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

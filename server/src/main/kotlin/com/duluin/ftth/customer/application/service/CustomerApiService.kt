package com.duluin.ftth.customer.application.service

import com.duluin.ftth.common.domain.error.ConflictException
import com.duluin.ftth.common.domain.error.NotFoundException
import com.duluin.ftth.customer.BillableSubscription
import com.duluin.ftth.customer.CustomerApi
import com.duluin.ftth.customer.CustomerPlacement
import com.duluin.ftth.customer.CustomerRef
import com.duluin.ftth.customer.OdpOccupant
import com.duluin.ftth.customer.OnuPlacementRef
import com.duluin.ftth.customer.OnuRef
import com.duluin.ftth.customer.ProvisionOnuCommand
import com.duluin.ftth.customer.RegisterCustomerCommand
import com.duluin.ftth.customer.SubscriberStats
import com.duluin.ftth.customer.SubscriptionRef
import com.duluin.ftth.customer.domain.model.Customer
import com.duluin.ftth.customer.domain.model.OnuStatus
import com.duluin.ftth.customer.application.port.inbound.AttachOnuCommand
import com.duluin.ftth.customer.application.port.inbound.ManageCustomerUseCase
import com.duluin.ftth.customer.application.port.inbound.ManageOnuUseCase
import com.duluin.ftth.customer.application.port.inbound.ManageSubscriptionUseCase
import com.duluin.ftth.customer.application.port.inbound.RegisterOnuCommand
import com.duluin.ftth.customer.application.port.inbound.SaveCustomerCommand
import com.duluin.ftth.customer.application.port.inbound.SaveSubscriptionCommand
import com.duluin.ftth.customer.application.port.outbound.CustomerRepository
import com.duluin.ftth.customer.application.port.outbound.CustomerTileRenderer
import com.duluin.ftth.customer.application.port.outbound.OnuRepository
import com.duluin.ftth.customer.application.port.outbound.SubscriptionRepository
import com.duluin.ftth.customer.domain.model.Subscription
import com.duluin.ftth.customer.domain.model.SubscriptionStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
@Transactional(readOnly = true)
class CustomerApiService(
    private val customerRepository: CustomerRepository,
    private val onuRepository: OnuRepository,
    private val subscriptionRepository: SubscriptionRepository,
    private val tileRenderer: CustomerTileRenderer,
    private val manageOnu: ManageOnuUseCase,
    private val manageCustomer: ManageCustomerUseCase,
    private val manageSubscription: ManageSubscriptionUseCase,
) : CustomerApi {

    override fun renderMapTile(z: Int, x: Int, y: Int, areaIds: Set<UUID>?): ByteArray =
        tileRenderer.render(z, x, y, areaIds)

    override fun findCustomer(id: UUID): CustomerRef? = customerRepository.findById(id)?.toRef()

    override fun findCustomersByIds(ids: Set<UUID>): List<CustomerRef> =
        if (ids.isEmpty()) emptyList() else customerRepository.findAllByIds(ids).map { it.toRef() }

    override fun findSubscription(id: UUID): SubscriptionRef? =
        subscriptionRepository.findById(id)?.toRef()

    override fun findSubscriptionsByCustomer(customerId: UUID): List<SubscriptionRef> =
        subscriptionRepository.findByCustomerIds(setOf(customerId)).map { it.toRef() }

    override fun findAwaitingInstallation(areaIds: Set<UUID>?): List<CustomerRef> =
        customerRepository.findAwaitingInstallation(areaIds).map { it.toRef() }

    /**
     * Pelanggan bisa punya beberapa ONU (mis. unit cadangan yang belum dibongkar);
     * yang dilaporkan adalah yang benar-benar terpasang di ODP.
     */
    override fun findPlacementOf(customerId: UUID): CustomerPlacement? =
        onuRepository.findByCustomerId(customerId)
            .firstOrNull { it.attached }
            ?.let { onu ->
                CustomerPlacement(
                    onuId = onu.id,
                    odpId = onu.odpId!!,
                    portNumber = onu.odpPortNumber!!,
                    onuSerialNumber = onu.serialNumber,
                    onuStatus = onu.status.name,
                    opticalHealth = onu.opticalHealth().name,
                    installRxPowerDbm = onu.installRxPowerDbm,
                )
            }

    /**
     * Menyusun isi sebuah ODP dalam tiga query tetap (ONU → pelanggan → langganan),
     * berapa pun jumlah penghuninya.
     */
    override fun findOccupantsOfOdp(odpId: UUID): List<OdpOccupant> {
        val onus = onuRepository.findByOdpId(odpId)
        if (onus.isEmpty()) return emptyList()

        val customerIds = onus.mapTo(HashSet()) { it.customerId }
        val customers = customerRepository.findAllByIds(customerIds).associateBy { it.id }
        val activeSubscription = subscriptionRepository.findByCustomerIds(customerIds)
            .groupBy { it.customerId }
            // Yang ditampilkan adalah langganan yang paling menggambarkan kondisi
            // sekarang: yang aktif dulu, baru yang lain.
            .mapValues { (_, subs) ->
                subs.firstOrNull { it.status == SubscriptionStatus.ACTIVE } ?: subs.firstOrNull()
            }

        return onus.mapNotNull { onu ->
            val customer = customers[onu.customerId] ?: return@mapNotNull null
            val port = onu.odpPortNumber ?: return@mapNotNull null
            val subscription = activeSubscription[customer.id]
            OdpOccupant(
                portNumber = port,
                customerId = customer.id,
                customerCode = customer.code,
                customerName = customer.name,
                phone = customer.phone,
                location = customer.location,
                onuId = onu.id,
                onuSerialNumber = onu.serialNumber,
                onuStatus = onu.status.name,
                opticalHealth = onu.opticalHealth().name,
                installRxPowerDbm = onu.installRxPowerDbm,
                subscriptionPackage = subscription?.packageName,
                subscriptionStatus = subscription?.status?.name,
            )
        }.sortedBy { it.portNumber }
    }

    override fun findOnusBySerialNumbers(serialNumbers: Set<String>): List<OnuRef> {
        if (serialNumbers.isEmpty()) return emptyList()
        val onus = onuRepository.findBySerialNumbers(serialNumbers.mapTo(HashSet()) { it.trim().uppercase() })
        val customerNames = customerRepository.findAllByIds(onus.mapTo(HashSet()) { it.customerId })
            .associate { it.id to it.name }
        return onus.map { onu ->
            OnuRef(
                id = onu.id,
                serialNumber = onu.serialNumber,
                customerId = onu.customerId,
                customerName = customerNames[onu.customerId].orEmpty(),
                odpId = onu.odpId,
                status = onu.status.name,
            )
        }
    }

    @Transactional
    override fun recordObservedOnuStatuses(statuses: Map<UUID, String>): Int {
        if (statuses.isEmpty()) return 0
        var changed = 0
        onuRepository.findAllByIds(statuses.keys).forEach { onu ->
            val observed = statuses[onu.id]?.let { runCatching { OnuStatus.valueOf(it) }.getOrNull() }
                ?: return@forEach
            // ONU yang sudah dibongkar sengaja tidak diikutkan: perangkat lama yang
            // masih menyala di tangan pelanggan tidak boleh menghidupkannya kembali
            // di data seolah layanannya aktif.
            if (onu.status == OnuStatus.DISMANTLED || onu.status == observed) return@forEach
            onu.changeStatus(observed)
            onuRepository.save(onu)
            changed++
        }
        return changed
    }

    /**
     * Daftarkan-atau-pakai-ulang lalu pasang, memakai kembali use case yang sama
     * dengan pemasangan manual sehingga audit dan aturan port ikut berlaku. Serial
     * yang sudah terdaftar untuk pelanggan yang sama dipakai ulang — memungkinkan
     * memasang ONU yang tadinya terdaftar tanpa terpasang.
     */
    @Transactional
    override fun provisionOnu(command: ProvisionOnuCommand): OnuRef {
        val customer = customerRepository.findById(command.customerId)
            ?: throw NotFoundException("Pelanggan ${command.customerId} tidak ditemukan")
        val serial = command.serialNumber.trim().uppercase()
        val existing = onuRepository.findBySerialNumbers(setOf(serial)).firstOrNull()
        val onuId = if (existing != null) {
            if (existing.customerId != command.customerId) {
                throw ConflictException("ONU $serial sudah terdaftar pada pelanggan lain")
            }
            existing.id
        } else {
            manageOnu.register(command.customerId, RegisterOnuCommand(command.serialNumber, command.model)).id
        }
        val onu = manageOnu.attach(
            onuId,
            AttachOnuCommand(command.odpId, command.portNumber, command.installRxPowerDbm),
        )
        return OnuRef(
            id = onu.id,
            serialNumber = onu.serialNumber,
            customerId = onu.customerId,
            customerName = customer.name,
            odpId = onu.odpId,
            status = onu.status.name,
        )
    }

    override fun placementsForOnus(onuIds: Set<UUID>): List<OnuPlacementRef> =
        if (onuIds.isEmpty()) emptyList()
        else onuRepository.findAllByIds(onuIds).map { OnuPlacementRef(it.id, it.customerId, it.odpId) }

    override fun occupiedPortsOn(odpId: UUID): Set<Int> =
        onuRepository.findByOdpId(odpId).mapNotNullTo(HashSet()) { it.odpPortNumber }

    override fun countOccupantsByOdp(odpIds: Set<UUID>): Map<UUID, Long> =
        onuRepository.countByOdpIds(odpIds)

    override fun findBillableSubscriptions(): List<BillableSubscription> =
        subscriptionRepository.findBillableForCurrentTenant().map { it.toBillable() }

    override fun findBillableSubscription(subscriptionId: UUID): BillableSubscription? =
        subscriptionRepository.findById(subscriptionId)?.toBillable()

    override fun subscriberStats(): SubscriberStats {
        val byStatus = subscriptionRepository.countByStatus()
        return SubscriberStats(
            totalCustomers = customerRepository.count().toInt(),
            subscriptionsByStatus = byStatus.entries.associate { (s, n) -> s.name to n.toInt() },
            billableCount = ((byStatus[SubscriptionStatus.ACTIVE] ?: 0) + (byStatus[SubscriptionStatus.ISOLATED] ?: 0)).toInt(),
            mrr = subscriptionRepository.sumMonthlyRecurringRevenue(),
        )
    }

    /**
     * Isolir/pulih dari billing memakai kembali use case langganan yang sama dengan
     * kendali manual, sehingga audit & event ikut berjalan. No-op bila status tak sesuai
     * (idempoten terhadap penegakan/pemulihan berulang).
     */
    @Transactional
    override fun isolateForBilling(subscriptionId: UUID) {
        val subscription = subscriptionRepository.findById(subscriptionId) ?: return
        if (subscription.status == SubscriptionStatus.ACTIVE) manageSubscription.isolate(subscriptionId)
    }

    @Transactional
    override fun reactivateForBilling(subscriptionId: UUID) {
        val subscription = subscriptionRepository.findById(subscriptionId) ?: return
        if (subscription.status == SubscriptionStatus.ISOLATED) manageSubscription.activate(subscriptionId)
    }

    /**
     * Aktivasi/terminasi dari penyelesaian work order memakai kembali use case langganan yang
     * sama dengan kendali manual, sehingga audit & event (SubscriptionActivated/Terminated,
     * yang memicu sinkron akses & billing) ikut berjalan. No-op bila status tak sesuai —
     * idempoten terhadap penyelesaian ulang WO.
     */
    @Transactional
    override fun activateForInstallation(subscriptionId: UUID) {
        val subscription = subscriptionRepository.findById(subscriptionId) ?: return
        if (subscription.status == SubscriptionStatus.PENDING) manageSubscription.activate(subscriptionId)
    }

    @Transactional
    override fun terminateForDismantle(subscriptionId: UUID) {
        val subscription = subscriptionRepository.findById(subscriptionId) ?: return
        if (subscription.status != SubscriptionStatus.TERMINATED) manageSubscription.terminate(subscriptionId)
    }

    /**
     * Onboarding memakai kembali use case pembuatan yang sama dengan jalur manual (controller),
     * jadi validasi kode unik, audit, dan event ikut berjalan — orkestrasi tak menembus internal.
     */
    @Transactional
    override fun registerCustomer(command: RegisterCustomerCommand): UUID =
        manageCustomer.create(
            SaveCustomerCommand(
                code = command.code,
                name = command.name,
                phone = command.phone,
                email = command.email,
                address = command.address,
                location = command.location,
                areaId = command.areaId,
                idCardNumber = command.idCardNumber,
            ),
        ).id

    @Transactional
    override fun openSubscription(customerId: UUID, planId: UUID, monthlyFeeOverride: java.math.BigDecimal?): UUID =
        manageSubscription.create(customerId, SaveSubscriptionCommand(planId, monthlyFeeOverride)).id

    private fun Subscription.toBillable() = BillableSubscription(
        subscriptionId = id,
        customerId = customerId,
        packageName = packageName,
        monthlyFee = monthlyFee,
        status = status.name,
        activatedAt = activatedAt,
        prorateOnActivation = prorateOnActivation,
        billingDayOfMonth = billingDayOfMonth,
        graceDays = graceDays,
        autoIsolir = autoIsolir,
    )

    private fun Subscription.toRef() = SubscriptionRef(
        id = id,
        customerId = customerId,
        planId = planId,
        packageName = packageName,
        bandwidthMbps = bandwidthMbps,
        status = status.name,
    )

    private fun Customer.toRef() = CustomerRef(
        id = id,
        code = code,
        name = name,
        phone = phone,
        location = location,
        status = status.name,
    )
}

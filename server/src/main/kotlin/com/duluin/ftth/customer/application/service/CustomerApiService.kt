package com.duluin.ftth.customer.application.service

import com.duluin.ftth.common.domain.error.ConflictException
import com.duluin.ftth.common.domain.error.NotFoundException
import com.duluin.ftth.customer.CustomerApi
import com.duluin.ftth.customer.CustomerPlacement
import com.duluin.ftth.customer.CustomerRef
import com.duluin.ftth.customer.OdpOccupant
import com.duluin.ftth.customer.OnuPlacementRef
import com.duluin.ftth.customer.OnuRef
import com.duluin.ftth.customer.ProvisionOnuCommand
import com.duluin.ftth.customer.domain.model.Customer
import com.duluin.ftth.customer.domain.model.OnuStatus
import com.duluin.ftth.customer.application.port.inbound.AttachOnuCommand
import com.duluin.ftth.customer.application.port.inbound.ManageOnuUseCase
import com.duluin.ftth.customer.application.port.inbound.RegisterOnuCommand
import com.duluin.ftth.customer.application.port.outbound.CustomerRepository
import com.duluin.ftth.customer.application.port.outbound.CustomerTileRenderer
import com.duluin.ftth.customer.application.port.outbound.OnuRepository
import com.duluin.ftth.customer.application.port.outbound.SubscriptionRepository
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
) : CustomerApi {

    override fun renderMapTile(z: Int, x: Int, y: Int, areaIds: Set<UUID>?): ByteArray =
        tileRenderer.render(z, x, y, areaIds)

    override fun findCustomer(id: UUID): CustomerRef? = customerRepository.findById(id)?.toRef()

    override fun findCustomersByIds(ids: Set<UUID>): List<CustomerRef> =
        if (ids.isEmpty()) emptyList() else customerRepository.findAllByIds(ids).map { it.toRef() }

    /**
     * Pelanggan bisa punya beberapa ONU (mis. unit cadangan yang belum dibongkar);
     * yang dilaporkan adalah yang benar-benar terpasang di ODP.
     */
    override fun findPlacementOf(customerId: UUID): CustomerPlacement? =
        onuRepository.findByCustomerId(customerId)
            .firstOrNull { it.attached }
            ?.let { onu ->
                CustomerPlacement(
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

    private fun Customer.toRef() = CustomerRef(
        id = id,
        code = code,
        name = name,
        phone = phone,
        location = location,
        status = status.name,
    )
}

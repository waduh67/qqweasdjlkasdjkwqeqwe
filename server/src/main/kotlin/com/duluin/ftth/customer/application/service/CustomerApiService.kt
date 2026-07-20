package com.duluin.ftth.customer.application.service

import com.duluin.ftth.customer.CustomerApi
import com.duluin.ftth.customer.CustomerPlacement
import com.duluin.ftth.customer.CustomerRef
import com.duluin.ftth.customer.OdpOccupant
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
) : CustomerApi {

    override fun renderMapTile(z: Int, x: Int, y: Int, areaIds: Set<UUID>?): ByteArray =
        tileRenderer.render(z, x, y, areaIds)

    override fun findCustomer(id: UUID): CustomerRef? = customerRepository.findById(id)?.let {
        CustomerRef(
            id = it.id,
            code = it.code,
            name = it.name,
            phone = it.phone,
            location = it.location,
            status = it.status.name,
        )
    }

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
                onuSerialNumber = onu.serialNumber,
                onuStatus = onu.status.name,
                opticalHealth = onu.opticalHealth().name,
                installRxPowerDbm = onu.installRxPowerDbm,
                subscriptionPackage = subscription?.packageName,
                subscriptionStatus = subscription?.status?.name,
            )
        }.sortedBy { it.portNumber }
    }

    override fun occupiedPortsOn(odpId: UUID): Set<Int> =
        onuRepository.findByOdpId(odpId).mapNotNullTo(HashSet()) { it.odpPortNumber }

    override fun countOccupantsByOdp(odpIds: Set<UUID>): Map<UUID, Long> =
        onuRepository.countByOdpIds(odpIds)
}

package com.duluin.ftth.monitoring.application.service

import com.duluin.ftth.common.domain.error.ConflictException
import com.duluin.ftth.common.domain.error.NotFoundException
import com.duluin.ftth.customer.CustomerApi
import com.duluin.ftth.customer.ProvisionOnuCommand
import com.duluin.ftth.monitoring.application.port.inbound.DiscoveredOnuView
import com.duluin.ftth.monitoring.application.port.inbound.ManageDiscoveredOnuUseCase
import com.duluin.ftth.monitoring.application.port.inbound.ProvisionDiscoveredOnuCommand
import com.duluin.ftth.monitoring.application.port.outbound.DiscoveredOnuRepository
import com.duluin.ftth.monitoring.domain.model.DiscoveredOnu
import com.duluin.ftth.monitoring.domain.model.DiscoveredOnuState
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Sisi operator dari kotak masuk provisioning. Penautan ONU nyata (daftar +
 * pasang ke port ODP) didelegasikan ke module customer lewat `CustomerApi`;
 * service ini hanya memutuskan baris mana yang selesai dan menandainya.
 */
@Service
@Transactional(readOnly = true)
class DiscoveredOnuService(
    private val repository: DiscoveredOnuRepository,
    private val customerApi: CustomerApi,
) : ManageDiscoveredOnuUseCase {

    override fun list(state: DiscoveredOnuState?): List<DiscoveredOnuView> =
        repository.findByState(state ?: DiscoveredOnuState.DISCOVERED).map { it.toView() }

    @Transactional
    override fun provision(id: UUID, command: ProvisionDiscoveredOnuCommand): DiscoveredOnuView {
        val discovered = require(id)
        if (discovered.state == DiscoveredOnuState.PROVISIONED) {
            throw ConflictException("ONU ${discovered.serialNumber} sudah diprovisikan")
        }
        customerApi.provisionOnu(
            ProvisionOnuCommand(
                serialNumber = discovered.serialNumber,
                model = null,
                customerId = command.customerId,
                odpId = command.odpId,
                portNumber = command.portNumber,
                // Bila operator tak mengisi baseline, pakai redaman terakhir yang teramati.
                installRxPowerDbm = command.installRxPowerDbm ?: discovered.lastRxPowerDbm,
            ),
        )
        discovered.markProvisioned()
        return repository.save(discovered).toView()
    }

    @Transactional
    override fun ignore(id: UUID): DiscoveredOnuView {
        val discovered = require(id)
        discovered.ignore()
        return repository.save(discovered).toView()
    }

    private fun require(id: UUID): DiscoveredOnu =
        repository.findById(id) ?: throw NotFoundException("ONU terdeteksi $id tidak ditemukan")

    private fun DiscoveredOnu.toView() = DiscoveredOnuView(
        id = id,
        serialNumber = serialNumber,
        oltId = oltId,
        oltCode = oltCode,
        ponPortLabel = ponPortLabel,
        lastStatus = lastStatus,
        lastRxPowerDbm = lastRxPowerDbm,
        firstSeenAt = firstSeenAt,
        lastSeenAt = lastSeenAt,
        seenCount = seenCount,
        state = state,
    )
}

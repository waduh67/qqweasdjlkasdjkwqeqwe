package com.duluin.ftth.customer.application.port.inbound

import com.duluin.ftth.customer.domain.model.OnuStatus
import java.util.UUID

interface ManageOnuUseCase {

    fun listForCustomer(customerId: UUID): List<OnuView>

    fun register(customerId: UUID, command: RegisterOnuCommand): OnuView

    /**
     * Memasang ONU ke port ODP tertentu. Kelayakan port (kapasitas, keterisian,
     * status ODP) ditegakkan module network lewat `NetworkApi`.
     */
    fun attach(id: UUID, command: AttachOnuCommand): OnuView

    fun detach(id: UUID): OnuView

    fun changeStatus(id: UUID, status: OnuStatus): OnuView

    fun delete(id: UUID)
}

data class RegisterOnuCommand(
    val serialNumber: String,
    val model: String?,
)

data class AttachOnuCommand(
    val odpId: UUID,
    val portNumber: Int,
    /** Redaman terukur saat instalasi; menjadi baseline deteksi degradasi. */
    val installRxPowerDbm: Double?,
)

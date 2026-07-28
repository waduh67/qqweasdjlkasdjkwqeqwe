package com.duluin.ftth.catalog.application.port.inbound

import com.duluin.ftth.catalog.domain.model.ServiceType
import java.math.BigDecimal
import java.util.UUID

/** Kelola katalog paket internet milik tenant. */
interface ManagePlanUseCase {

    fun list(): List<PlanView>

    fun get(id: UUID): PlanView

    fun create(command: SavePlanCommand): PlanView

    fun update(id: UUID, command: SavePlanCommand): PlanView
}

/**
 * Perintah simpan paket. Cerminan atribut domain, tapi tanpa aturan (validasi ada di
 * agregat `Plan` agar konsisten dari mana pun perubahannya datang). Field opsional
 * bernilai null = tak dipakai / ikut kebijakan global.
 */
data class SavePlanCommand(
    val name: String,
    val description: String?,
    val price: BigDecimal,
    val downMbps: Int,
    val upMbps: Int,
    val downBurstMbps: Int?,
    val upBurstMbps: Int?,
    val downThresholdMbps: Int?,
    val upThresholdMbps: Int?,
    val burstTimeSec: Int?,
    val downMinMbps: Int?,
    val upMinMbps: Int?,
    val priority: Int,
    val connectionLimit: Int?,
    val fupEnabled: Boolean,
    val fupQuotaMb: Long?,
    val fupDownMbps: Int?,
    val fupUpMbps: Int?,
    val serviceTypes: Set<ServiceType>,
    val prorateOnActivation: Boolean?,
    val billingDayOfMonth: Int?,
    val dueDays: Int?,
    val graceDays: Int?,
    val autoIsolir: Boolean?,
    val active: Boolean,
)

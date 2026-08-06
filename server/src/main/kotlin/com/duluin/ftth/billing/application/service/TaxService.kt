package com.duluin.ftth.billing.application.service

import com.duluin.ftth.billing.application.port.inbound.ManageTaxSettingsUseCase
import com.duluin.ftth.billing.application.port.inbound.TaxObligationView
import com.duluin.ftth.billing.application.port.inbound.TaxSettingsView
import com.duluin.ftth.billing.application.port.inbound.UpdateTaxSettingsCommand
import com.duluin.ftth.billing.application.port.inbound.ViewTaxObligationUseCase
import com.duluin.ftth.billing.application.port.outbound.BillingTaxSettingsRepository
import com.duluin.ftth.billing.application.port.outbound.InvoiceRepository
import com.duluin.ftth.billing.domain.model.BillingTaxSettings
import com.duluin.ftth.common.domain.error.ValidationException
import com.duluin.ftth.common.infrastructure.audit.AuditRecorder
import com.duluin.ftth.common.tenant.TenantContext
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.ZoneId

/**
 * Sisi operator setelan pajak + penghitungan kewajiban pajak/kontribusi.
 *
 * Perubahan setelan dicatat ke audit: menyalakan PPN berarti pelanggan mulai ditagih pajak,
 * jadi harus jelas siapa & kapan. Kewajiban BHP/USO dihitung DI SERVER dari tagihan lunas
 * (satu sumber kebenaran uang), memakai peredaran bruto sebelum PPN sebagai dasar.
 */
@Service
@Transactional(readOnly = true)
class TaxService(
    private val settingsRepository: BillingTaxSettingsRepository,
    private val invoiceRepository: InvoiceRepository,
    private val auditor: AuditRecorder,
) : ManageTaxSettingsUseCase, ViewTaxObligationUseCase {

    override fun get(): TaxSettingsView = current().toView()

    @Transactional
    override fun update(command: UpdateTaxSettingsCommand): TaxSettingsView {
        val settings = current()
        settings.update(
            ppnEnabled = command.ppnEnabled,
            ppnRate = command.ppnRate,
            regulatoryEnabled = command.regulatoryEnabled,
            bhpRate = command.bhpRate,
            usoRate = command.usoRate,
        )
        val saved = settingsRepository.save(settings)
        auditor.record("billing.taxsettings.updated", "BillingTaxSettings", saved.id, saved.tenantId)
        return saved.toView()
    }

    override fun obligation(from: LocalDate, to: LocalDate): TaxObligationView {
        if (from.isAfter(to)) throw ValidationException("Tanggal mulai tak boleh setelah tanggal akhir")
        val settings = current()
        val fromInstant = from.atStartOfDay(zone).toInstant()
        val toExclusive = to.plusDays(1).atStartOfDay(zone).toInstant()

        val paid = invoiceRepository.findPaidBetween(fromInstant, toExclusive)
        val ppnCollected = paid.fold(BigDecimal.ZERO) { acc, inv -> acc + inv.taxAmount }.setScale(2, RoundingMode.HALF_UP)
        val revenueBase = paid.fold(BigDecimal.ZERO) { acc, inv -> acc + inv.baseAmount }.setScale(2, RoundingMode.HALF_UP)

        // BHP/USO hanya dihitung bila pelaporan aktif; nonaktif → kewajiban nol.
        val bhp = if (settings.regulatoryEnabled) revenueBase.multiply(settings.bhpRate).setScale(2, RoundingMode.HALF_UP) else ZERO_MONEY
        val uso = if (settings.regulatoryEnabled) revenueBase.multiply(settings.usoRate).setScale(2, RoundingMode.HALF_UP) else ZERO_MONEY

        return TaxObligationView(
            from = from,
            to = to,
            ppnEnabled = settings.ppnEnabled,
            ppnRate = settings.ppnRate,
            ppnCollected = ppnCollected,
            regulatoryEnabled = settings.regulatoryEnabled,
            bhpRate = settings.bhpRate,
            usoRate = settings.usoRate,
            regulatoryRevenueBase = revenueBase,
            bhpAmount = bhp,
            usoAmount = uso,
            regulatoryObligation = bhp.add(uso),
        )
    }

    private fun current(): BillingTaxSettings =
        settingsRepository.find() ?: BillingTaxSettings.defaultFor(TenantContext.tenantId())

    private fun BillingTaxSettings.toView() = TaxSettingsView(
        ppnEnabled = ppnEnabled,
        ppnRate = ppnRate,
        regulatoryEnabled = regulatoryEnabled,
        bhpRate = bhpRate,
        usoRate = usoRate,
    )

    private companion object {
        /** Batas hari→instant memakai zona server, selaras penjadwal & BillingApiService. */
        val zone: ZoneId = ZoneId.systemDefault()
        val ZERO_MONEY: BigDecimal = BigDecimal.ZERO.setScale(2)
    }
}

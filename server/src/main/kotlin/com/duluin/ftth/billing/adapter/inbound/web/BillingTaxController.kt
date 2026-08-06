package com.duluin.ftth.billing.adapter.inbound.web

import com.duluin.ftth.billing.application.port.inbound.ManageTaxSettingsUseCase
import com.duluin.ftth.billing.application.port.inbound.TaxObligationView
import com.duluin.ftth.billing.application.port.inbound.TaxSettingsView
import com.duluin.ftth.billing.application.port.inbound.UpdateTaxSettingsCommand
import com.duluin.ftth.billing.application.port.inbound.ViewTaxObligationUseCase
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.math.BigDecimal
import java.time.LocalDate

/**
 * Setelan pajak tenant (PPN yang ditagihkan ke pelanggan) + kewajiban pelaporan BHP/USO.
 * Tarif dikirim/diterima sebagai PECAHAN (mis. 0.11 untuk 11%), bukan persen — konversi ke
 * persen adalah urusan UI. Kewajiban BHP/USO dihitung server dari tagihan lunas.
 */
@RestController
@RequestMapping("/api/billing")
@Tag(name = "Billing — pajak & kontribusi")
@SecurityRequirement(name = "bearer-jwt")
class BillingTaxController(
    private val settings: ManageTaxSettingsUseCase,
    private val obligations: ViewTaxObligationUseCase,
) {

    @GetMapping("/tax-settings")
    @PreAuthorize("@authz.can('billing.tax.view')")
    @Operation(summary = "Setelan pajak tenant (PPN + BHP/USO)")
    fun getSettings(): TaxSettingsView = settings.get()

    @PutMapping("/tax-settings")
    @PreAuthorize("@authz.can('billing.tax.manage')")
    @Operation(summary = "Ubah setelan pajak tenant")
    fun updateSettings(@RequestBody request: TaxSettingsRequest): TaxSettingsView =
        settings.update(request.toCommand())

    @GetMapping("/tax-obligation")
    @PreAuthorize("@authz.can('billing.tax.view')")
    @Operation(summary = "PPN terkumpul + kewajiban BHP/USO (default tahun kalender berjalan)")
    fun obligation(
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) from: LocalDate?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) to: LocalDate?,
    ): TaxObligationView {
        // Kewajiban BHP/USO dilaporkan tahunan; default rentang = 1 Jan tahun berjalan s/d hari ini.
        val end = to ?: LocalDate.now()
        val start = from ?: LocalDate.of(end.year, 1, 1)
        return obligations.obligation(start, end)
    }
}

/** Body ubah setelan pajak; tarif pecahan di [0,1). Validasi rentang nilai ditegakkan di domain. */
data class TaxSettingsRequest(
    val ppnEnabled: Boolean,
    val ppnRate: BigDecimal,
    val regulatoryEnabled: Boolean,
    val bhpRate: BigDecimal,
    val usoRate: BigDecimal,
) {
    fun toCommand() = UpdateTaxSettingsCommand(
        ppnEnabled = ppnEnabled,
        ppnRate = ppnRate,
        regulatoryEnabled = regulatoryEnabled,
        bhpRate = bhpRate,
        usoRate = usoRate,
    )
}

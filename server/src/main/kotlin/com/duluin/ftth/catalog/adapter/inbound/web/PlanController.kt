package com.duluin.ftth.catalog.adapter.inbound.web

import com.duluin.ftth.catalog.application.port.inbound.ManagePlanUseCase
import com.duluin.ftth.catalog.application.port.inbound.PlanView
import com.duluin.ftth.catalog.application.port.inbound.SavePlanCommand
import com.duluin.ftth.catalog.domain.model.ServiceType
import com.duluin.ftth.common.domain.error.ValidationException
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import org.springframework.http.HttpStatus
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.math.BigDecimal
import java.util.UUID

/**
 * Katalog paket internet — SUMBER TUNGGAL harga + kecepatan + QoS + FUP + siklus billing.
 *
 * Aturan nilai (rentang, kaitan burst≥rate, FUP wajib lengkap) ditegakkan di domain
 * `Plan`; controller hanya mewajibkan field wajib terisi & memetakan tipe layanan.
 * Respons menyertakan `rateLimit` yang sudah dirakit agar preview UI persis dengan
 * yang ditulis ke RADIUS.
 */
@RestController
@RequestMapping("/api/catalog/plans")
@Tag(name = "Catalog — Paket internet")
@SecurityRequirement(name = "bearer-jwt")
class PlanController(
    private val plans: ManagePlanUseCase,
) {

    @GetMapping
    @PreAuthorize("@authz.can('catalog.plan.view')")
    @Operation(summary = "Daftar paket internet tenant")
    fun list(): List<PlanView> = plans.list()

    @GetMapping("/{id}")
    @PreAuthorize("@authz.can('catalog.plan.view')")
    @Operation(summary = "Detail satu paket")
    fun get(@PathVariable id: UUID): PlanView = plans.get(id)

    @PostMapping
    @PreAuthorize("@authz.can('catalog.plan.manage')")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Buat paket baru")
    fun create(@Valid @RequestBody request: SavePlanRequest): PlanView = plans.create(request.toCommand())

    @PutMapping("/{id}")
    @PreAuthorize("@authz.can('catalog.plan.manage')")
    @Operation(summary = "Ubah paket (nonaktifkan lewat active=false)")
    fun update(@PathVariable id: UUID, @Valid @RequestBody request: SavePlanRequest): PlanView =
        plans.update(id, request.toCommand())
}

/**
 * Permintaan simpan paket. Field opsional (burst/threshold/limit-at/FUP/override siklus)
 * boleh null. [serviceTypes] berupa nama enum; nilai tak dikenal ditolak lugas.
 */
data class SavePlanRequest(
    @field:NotBlank val name: String,
    val description: String?,
    val price: BigDecimal,
    val downMbps: Int,
    val upMbps: Int,
    val downBurstMbps: Int? = null,
    val upBurstMbps: Int? = null,
    val downThresholdMbps: Int? = null,
    val upThresholdMbps: Int? = null,
    val burstTimeSec: Int? = null,
    val downMinMbps: Int? = null,
    val upMinMbps: Int? = null,
    val priority: Int = 8,
    val connectionLimit: Int? = null,
    val fupEnabled: Boolean = false,
    val fupQuotaMb: Long? = null,
    val fupDownMbps: Int? = null,
    val fupUpMbps: Int? = null,
    val serviceTypes: Set<String> = setOf("PPPOE"),
    val prorateOnActivation: Boolean? = null,
    val billingDayOfMonth: Int? = null,
    val dueDays: Int? = null,
    val graceDays: Int? = null,
    val autoIsolir: Boolean? = null,
    val active: Boolean = true,
) {
    fun toCommand() = SavePlanCommand(
        name = name,
        description = description,
        price = price,
        downMbps = downMbps,
        upMbps = upMbps,
        downBurstMbps = downBurstMbps,
        upBurstMbps = upBurstMbps,
        downThresholdMbps = downThresholdMbps,
        upThresholdMbps = upThresholdMbps,
        burstTimeSec = burstTimeSec,
        downMinMbps = downMinMbps,
        upMinMbps = upMinMbps,
        priority = priority,
        connectionLimit = connectionLimit,
        fupEnabled = fupEnabled,
        fupQuotaMb = fupQuotaMb,
        fupDownMbps = fupDownMbps,
        fupUpMbps = fupUpMbps,
        serviceTypes = serviceTypes.mapTo(LinkedHashSet()) { raw ->
            runCatching { ServiceType.valueOf(raw.trim().uppercase()) }
                .getOrElse { throw ValidationException("Tipe layanan '$raw' tidak dikenal") }
        },
        prorateOnActivation = prorateOnActivation,
        billingDayOfMonth = billingDayOfMonth,
        dueDays = dueDays,
        graceDays = graceDays,
        autoIsolir = autoIsolir,
        active = active,
    )
}

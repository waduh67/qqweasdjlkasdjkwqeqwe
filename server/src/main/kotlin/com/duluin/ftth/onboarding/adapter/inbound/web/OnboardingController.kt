package com.duluin.ftth.onboarding.adapter.inbound.web

import com.duluin.ftth.common.domain.geo.Coordinate
import com.duluin.ftth.onboarding.application.port.inbound.ExpressOnboardingUseCase
import com.duluin.ftth.onboarding.application.port.inbound.ExpressPsbCommand
import com.duluin.ftth.onboarding.application.port.inbound.ExpressPsbResult
import com.duluin.ftth.onboarding.application.port.inbound.ImportPppoeCommand
import com.duluin.ftth.onboarding.application.port.inbound.ImportPppoeResult
import com.duluin.ftth.onboarding.application.port.inbound.ImportPppoeUseCase
import com.duluin.ftth.onboarding.application.port.inbound.ImportRow
import com.duluin.ftth.onboarding.application.port.inbound.ImportSource
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/**
 * PSB ekspres: satu POST untuk mendaftarkan pelanggan + langganan + akun jaringan + WO PSB
 * sekaligus (wizard onboarding operator). Digating izin union keempat aksi yang dirangkainya —
 * operator harus berhak melakukan setiap langkah, tanpa perlu izin baru yang harus disebar manual
 * ke role tenant lama.
 */
@RestController
@RequestMapping("/api/onboarding")
@Tag(name = "Onboarding")
@SecurityRequirement(name = "bearer-jwt")
class OnboardingController(
    private val onboarding: ExpressOnboardingUseCase,
    private val importPppoe: ImportPppoeUseCase,
) {

    @PostMapping("/psb")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(
        "@authz.canAll('customer.customer.create','customer.subscription.update','bng.access.manage','workorder.order.create')",
    )
    fun onboardPsb(@Valid @RequestBody request: ExpressPsbRequest): ExpressPsbResult =
        onboarding.onboardPsb(request.toCommand())

    /**
     * Bulk-import PPPoE dari RouterOS ke sistem (pelanggan+langganan+akun AKTIF+terprovisi RADIUS).
     * Digating union izin langkah yang dirangkainya + `bng.nas.manage` (baca `/ppp/secret` router
     * pada sumber NAS). Membalas rekap per-baris (bukan resource tunggal) → 200, bukan 201.
     */
    @PostMapping("/import/pppoe")
    @ResponseStatus(HttpStatus.OK)
    @PreAuthorize(
        "@authz.canAll('customer.customer.create','customer.subscription.update','bng.access.manage','bng.nas.manage')",
    )
    fun importPppoe(@Valid @RequestBody request: ImportPppoeRequest): ImportPppoeResult =
        importPppoe.importPppoe(request.toCommand())
}

/**
 * Muatan bulk-import PPPoE. [source] NAS → server menarik `/ppp/secret` dari [nasId] (abaikan
 * [rows]); INLINE → baris dari [rows] (hasil paste/upload operator). [profilePlanId] memetakan
 * profil RouterOS → paket; [defaultPlanId] fallback. [onlyNames] membatasi ke username terpilih.
 * [defaultAddress]/[defaultLocation] mengisi data pelanggan yang tak ada di router (placeholder).
 */
data class ImportPppoeRequest(
    @field:NotNull val nasId: UUID?,
    @field:NotNull val source: ImportSource?,
    @field:Valid val rows: List<ImportRowPayload> = emptyList(),
    val profilePlanId: Map<String, UUID> = emptyMap(),
    val defaultPlanId: UUID? = null,
    val skipDisabled: Boolean = true,
    val onlyNames: List<String>? = null,
    val areaId: UUID? = null,
    @field:Size(max = 500) val defaultAddress: String? = null,
    @field:Valid val defaultLocation: LocationPayload? = null,
) {
    fun toCommand() = ImportPppoeCommand(
        nasId = nasId!!,
        source = source!!,
        rows = rows.map { ImportRow(it.name!!, it.password, it.profile, it.comment, it.disabled) },
        profilePlanId = profilePlanId,
        defaultPlanId = defaultPlanId,
        skipDisabled = skipDisabled,
        onlyNames = onlyNames?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet(),
        areaId = areaId,
        defaultAddress = defaultAddress,
        defaultLocation = defaultLocation?.let { Coordinate(it.longitude, it.latitude) },
    )
}

/** Satu baris impor INLINE (paste/upload). [name] = username PPPoE; sisanya opsional. */
data class ImportRowPayload(
    @field:NotBlank @field:Size(max = 100) val name: String?,
    @field:Size(max = 100) val password: String? = null,
    @field:Size(max = 100) val profile: String? = null,
    @field:Size(max = 255) val comment: String? = null,
    val disabled: Boolean = false,
)

/** Titik lokasi pelanggan; longitude dulu (urutan GeoJSON/PostGIS), sama seperti form pelanggan. */
data class LocationPayload(
    @field:Min(-180) @field:Max(180) val longitude: Double,
    @field:Min(-90) @field:Max(90) val latitude: Double,
)

/**
 * Muatan PSB ekspres. Kredensial ([username]/[secret]) boleh kosong (server generate — operator
 * melihat password yang ia isi/generate di klien). [serviceType] kosong → PPPOE. [title] kosong →
 * "PSB {nama}".
 */
data class ExpressPsbRequest(
    // Pelanggan
    /** Kosong = server membuat kode berurut otomatis (`CUST-000001`). */
    @field:Size(max = 40) val code: String? = null,
    @field:NotBlank @field:Size(max = 150) val name: String?,
    @field:Size(max = 30) val phone: String? = null,
    @field:Size(max = 255) val email: String? = null,
    @field:NotBlank @field:Size(max = 500) val address: String?,
    @field:NotNull @field:Valid val location: LocationPayload?,
    val areaId: UUID? = null,
    // Langganan
    @field:NotNull val planId: UUID?,
    val monthlyFeeOverride: BigDecimal? = null,
    // Akun jaringan
    @field:Size(max = 100) val username: String? = null,
    @field:Size(max = 100) val secret: String? = null,
    @field:Size(max = 20) val serviceType: String? = null,
    val nasId: UUID? = null,
    @field:Size(max = 45) val framedIp: String? = null,
    // Work order PSB
    @field:Size(max = 200) val title: String? = null,
    @field:Size(max = 2000) val description: String? = null,
    val scheduledAt: Instant? = null,
    /** Roster teknisi awal WO PSB (tim datar); null/kosong = belum ditugaskan. */
    val assignees: Set<UUID>? = null,
) {
    fun toCommand() = ExpressPsbCommand(
        code = code,
        name = name!!,
        phone = phone,
        email = email,
        address = address!!,
        location = Coordinate(location!!.longitude, location.latitude),
        areaId = areaId,
        planId = planId!!,
        monthlyFeeOverride = monthlyFeeOverride,
        username = username,
        secret = secret,
        serviceType = serviceType,
        nasId = nasId,
        framedIp = framedIp,
        title = title,
        description = description,
        scheduledAt = scheduledAt,
        assignees = assignees ?: emptySet(),
    )
}

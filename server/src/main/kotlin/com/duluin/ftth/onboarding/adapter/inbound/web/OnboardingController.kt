package com.duluin.ftth.onboarding.adapter.inbound.web

import com.duluin.ftth.common.domain.geo.Coordinate
import com.duluin.ftth.onboarding.application.port.inbound.CustomerExportLine
import com.duluin.ftth.onboarding.application.port.inbound.CustomerImportRow
import com.duluin.ftth.onboarding.application.port.inbound.ExportCustomersUseCase
import com.duluin.ftth.onboarding.application.port.inbound.ExpressOnboardingUseCase
import com.duluin.ftth.onboarding.application.port.inbound.ExpressPsbCommand
import com.duluin.ftth.onboarding.application.port.inbound.ExpressPsbResult
import com.duluin.ftth.onboarding.application.port.inbound.ImportCustomersCommand
import com.duluin.ftth.onboarding.application.port.inbound.ImportCustomersResult
import com.duluin.ftth.onboarding.application.port.inbound.ImportCustomersUseCase
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
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
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
    private val importCustomers: ImportCustomersUseCase,
    private val exportCustomers: ExportCustomersUseCase,
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

    /**
     * Impor CSV pelanggan generik (upsert menurut `mikrotik_username`): buat/perbarui pelanggan +
     * langganan + akun jaringan per baris. Digating union izin langkah yang dirangkainya (buat &
     * ubah pelanggan, ubah langganan, kelola akun jaringan). Membalas rekap per-baris → 200.
     */
    @PostMapping("/import/customers")
    @ResponseStatus(HttpStatus.OK)
    @PreAuthorize(
        "@authz.canAll('customer.customer.create','customer.customer.update','customer.subscription.update','bng.access.manage')",
    )
    fun importCustomers(@Valid @RequestBody request: ImportCustomersRequest): ImportCustomersResult =
        importCustomers.importCustomers(request.toCommand())

    /**
     * Ekspor CSV pelanggan (kebalikan simetris impor) — satu baris per akun jaringan, kolom cocok
     * template impor sehingga hasilnya bisa diunggah kembali. `mikrotik_password` & `notes` selalu
     * KOSONG (rahasia tak diekspor; kolom kosong = "pertahankan" saat diimpor ulang). Digating izin
     * BACA union (lihat/telaah pelanggan, langganan, akun jaringan). Membalas `text/csv` sebagai
     * unduhan berkas.
     */
    @GetMapping("/export/customers", produces = ["text/csv"])
    @PreAuthorize(
        "@authz.canAll('customer.customer.view','customer.subscription.view','bng.access.view')",
    )
    fun exportCustomers(): ResponseEntity<ByteArray> {
        val csv = CustomerCsv.render(exportCustomers.exportCustomers())
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"pelanggan.csv\"")
            .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
            .body(csv.toByteArray(Charsets.UTF_8))
    }
}

/**
 * Penulis CSV pelanggan (satu-satunya tempat urutan & escaping kolom hidup, di sisi adapter web).
 * Header identik template impor agar keluaran ekspor bisa diunggah kembali tanpa penyesuaian.
 * Kolom `mikrotik_password` & `notes` selalu kosong.
 */
internal object CustomerCsv {

    /** Urutan kolom = template impor. Diubah = klien impor/ekspor harus ikut disesuaikan. */
    private val HEADER = listOf(
        "name", "phone", "address", "package_name", "connection_type", "installation_date",
        "mikrotik_username", "mikrotik_password", "email", "router_name", "id_card_number",
        "next_billing", "latitude", "longitude", "notes",
    )

    fun render(lines: List<CustomerExportLine>): String {
        val sb = StringBuilder()
        sb.append(HEADER.joinToString(",") { escape(it) }).append("\r\n")
        for (line in lines) {
            sb.append(
                listOf(
                    line.name.orEmpty(),
                    line.phone.orEmpty(),
                    line.address.orEmpty(),
                    line.packageName.orEmpty(),
                    line.connectionType,
                    line.installationDate?.toString().orEmpty(),
                    line.mikrotikUsername,
                    "", // mikrotik_password — sengaja kosong (rahasia tak diekspor)
                    line.email.orEmpty(),
                    line.routerName.orEmpty(),
                    line.idCardNumber.orEmpty(),
                    line.nextBillingDay?.toString().orEmpty(),
                    line.latitude?.toString().orEmpty(),
                    line.longitude?.toString().orEmpty(),
                    "", // notes — tak dipetakan ke model
                ).joinToString(",") { escape(it) },
            ).append("\r\n")
        }
        return sb.toString()
    }

    /** Escaping RFC-4180: bungkus tanda kutip bila mengandung koma/kutip/baris-baru; kutip digandakan. */
    private fun escape(value: String): String =
        if (value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
            "\"" + value.replace("\"", "\"\"") + "\""
        } else {
            value
        }
}

/**
 * Muatan impor CSV pelanggan: baris sudah diurai klien menjadi bentuk terstruktur. `mikrotik_username`
 * jadi kunci upsert (kosong = baris dilewati). Kolom lain opsional — pada jalur update yang kosong
 * dipertahankan.
 */
data class ImportCustomersRequest(
    @field:Valid val rows: List<CustomerImportRowPayload> = emptyList(),
) {
    fun toCommand() = ImportCustomersCommand(
        rows = rows.map {
            CustomerImportRow(
                name = it.name,
                phone = it.phone,
                address = it.address,
                packageName = it.packageName,
                connectionType = it.connectionType,
                installationDate = it.installationDate,
                mikrotikUsername = it.mikrotikUsername,
                mikrotikPassword = it.mikrotikPassword,
                email = it.email,
                routerName = it.routerName,
                idCardNumber = it.idCardNumber,
                nextBillingDay = it.nextBillingDay,
                latitude = it.latitude,
                longitude = it.longitude,
            )
        },
    )
}

/**
 * Satu baris impor CSV. [mikrotikUsername] = kunci upsert. [installationDate] format ISO (`YYYY-MM-DD`).
 * [nextBillingDay] hari tanggal tagih (di-clamp ≤28 di service). Batas panjang menahan payload absurd,
 * bukan menegakkan aturan bisnis (itu di domain/service, agar satu baris jelek tak menjatuhkan batch).
 */
data class CustomerImportRowPayload(
    @field:Size(max = 150) val name: String? = null,
    @field:Size(max = 30) val phone: String? = null,
    @field:Size(max = 500) val address: String? = null,
    @field:Size(max = 100) val packageName: String? = null,
    @field:Size(max = 30) val connectionType: String? = null,
    val installationDate: LocalDate? = null,
    @field:Size(max = 100) val mikrotikUsername: String? = null,
    @field:Size(max = 100) val mikrotikPassword: String? = null,
    @field:Size(max = 255) val email: String? = null,
    @field:Size(max = 100) val routerName: String? = null,
    @field:Size(max = 32) val idCardNumber: String? = null,
    val nextBillingDay: Int? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
)

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

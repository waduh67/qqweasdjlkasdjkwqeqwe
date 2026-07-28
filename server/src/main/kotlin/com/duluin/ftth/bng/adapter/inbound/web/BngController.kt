package com.duluin.ftth.bng.adapter.inbound.web

import com.duluin.ftth.bng.application.port.inbound.BrasSessionView
import com.duluin.ftth.bng.application.port.inbound.ControlSubscriberAccessUseCase
import com.duluin.ftth.bng.application.port.inbound.ManageNasUseCase
import com.duluin.ftth.bng.application.port.inbound.ManageSubscriberAccessUseCase
import com.duluin.ftth.bng.application.port.inbound.NasView
import com.duluin.ftth.bng.application.port.inbound.ProvisionAccessCommand
import com.duluin.ftth.bng.application.port.inbound.ResetSecretCommand
import com.duluin.ftth.bng.application.port.inbound.SaveNasCommand
import com.duluin.ftth.bng.application.port.inbound.SubscriberAccessView
import com.duluin.ftth.bng.application.port.inbound.TrafficHistoryView
import com.duluin.ftth.bng.application.port.inbound.UpdateAccessCommand
import com.duluin.ftth.bng.application.port.inbound.ViewBngSessionUseCase
import com.duluin.ftth.bng.domain.model.NasVendor
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import org.springframework.http.HttpStatus
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * BRAS/RADIUS (BNG): kelola registri BRAS dan identitas jaringan (akun PPPoE) pelanggan.
 *
 * Katalog paket kini di modul `catalog` (`/api/catalog/plans`) — akun cukup merujuk
 * `planId`. Aturan nilai (panjang, format username) ditegakkan di domain agar konsisten
 * dari mana pun perubahannya datang; controller hanya mewajibkan field terisi.
 */
@RestController
@RequestMapping("/api/bng")
@Tag(name = "BNG — BRAS/RADIUS & akun PPPoE")
@SecurityRequirement(name = "bearer-jwt")
class BngController(
    private val nas: ManageNasUseCase,
    private val access: ManageSubscriberAccessUseCase,
    private val control: ControlSubscriberAccessUseCase,
    private val sessions: ViewBngSessionUseCase,
) {
    // ---- BRAS/NAS ----

    @GetMapping("/nas")
    @PreAuthorize("@authz.can('bng.nas.view')")
    @Operation(summary = "Daftar BRAS tenant")
    fun listNas(): List<NasView> = nas.list()

    @GetMapping("/nas/{id}")
    @PreAuthorize("@authz.can('bng.nas.view')")
    @Operation(summary = "Detail satu BRAS")
    fun getNas(@PathVariable id: UUID): NasView = nas.get(id)

    @PostMapping("/nas")
    @PreAuthorize("@authz.can('bng.nas.manage')")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Daftarkan BRAS baru")
    fun createNas(@Valid @RequestBody request: SaveNasRequest): NasView = nas.create(request.toCommand())

    @PutMapping("/nas/{id}")
    @PreAuthorize("@authz.can('bng.nas.manage')")
    @Operation(summary = "Ubah BRAS (secret kosong = biarkan apa adanya)")
    fun updateNas(@PathVariable id: UUID, @Valid @RequestBody request: SaveNasRequest): NasView =
        nas.update(id, request.toCommand())

    @DeleteMapping("/nas/{id}")
    @PreAuthorize("@authz.can('bng.nas.manage')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Hapus BRAS (ditolak bila masih menaungi akun)")
    fun deleteNas(@PathVariable id: UUID) = nas.delete(id)

    // ---- Akun PPPoE (identitas jaringan) ----

    @GetMapping("/access")
    @PreAuthorize("@authz.can('bng.access.view')")
    @Operation(summary = "Akun PPPoE milik satu pelanggan")
    fun accessForCustomer(@RequestParam customerId: UUID): List<SubscriberAccessView> =
        access.listForCustomer(customerId)

    @GetMapping("/subscriptions/{subscriptionId}/access")
    @PreAuthorize("@authz.can('bng.access.view')")
    @Operation(summary = "Akun PPPoE untuk satu langganan (0..1)")
    fun accessForSubscription(@PathVariable subscriptionId: UUID): List<SubscriberAccessView> =
        access.listForSubscription(subscriptionId)

    @GetMapping("/access/{id}")
    @PreAuthorize("@authz.can('bng.access.view')")
    @Operation(summary = "Detail satu akun PPPoE")
    fun getAccess(@PathVariable id: UUID): SubscriberAccessView = access.get(id)

    @PostMapping("/access")
    @PreAuthorize("@authz.can('bng.access.manage')")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Buat akun PPPoE untuk sebuah langganan")
    fun provisionAccess(@Valid @RequestBody request: ProvisionAccessRequest): SubscriberAccessView =
        access.provision(request.toCommand())

    @PutMapping("/access/{id}")
    @PreAuthorize("@authz.can('bng.access.manage')")
    @Operation(summary = "Ganti paket dan/atau BRAS sebuah akun")
    fun updateAccess(@PathVariable id: UUID, @Valid @RequestBody request: UpdateAccessRequest): SubscriberAccessView =
        access.updateAssignment(id, request.toCommand())

    @PostMapping("/access/{id}/reset-secret")
    @PreAuthorize("@authz.can('bng.access.manage')")
    @Operation(summary = "Ganti password PPPoE")
    fun resetSecret(@PathVariable id: UUID, @Valid @RequestBody request: ResetSecretRequest): SubscriberAccessView =
        access.resetSecret(id, request.toCommand())

    @DeleteMapping("/access/{id}")
    @PreAuthorize("@authz.can('bng.access.manage')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Hapus akun PPPoE")
    fun deleteAccess(@PathVariable id: UUID) = access.delete(id)

    // ---- Kendali jaringan (jalur tulis ke BRAS) ----

    @PostMapping("/access/{id}/isolate")
    @PreAuthorize("@authz.can('bng.access.isolate')")
    @Operation(summary = "Isolir akun: potong akses + antre DISCONNECT")
    fun isolateAccess(@PathVariable id: UUID): SubscriberAccessView = control.isolate(id)

    @PostMapping("/access/{id}/restore")
    @PreAuthorize("@authz.can('bng.access.isolate')")
    @Operation(summary = "Pulihkan akun dari isolir")
    fun restoreAccess(@PathVariable id: UUID): SubscriberAccessView = control.restore(id)

    @PostMapping("/access/{id}/reset-login")
    @PreAuthorize("@authz.can('bng.session.reset')")
    @Operation(summary = "Reset Login: putus sesi PPPoE agar CPE dial ulang")
    fun resetLogin(@PathVariable id: UUID): SubscriberAccessView = control.resetLogin(id)

    // ---- Sesi PPPoE & tren trafik (jalur baca BRAS) ----

    @GetMapping("/access/{id}/session")
    @PreAuthorize("@authz.can('bng.session.view')")
    @Operation(summary = "Keadaan sesi PPPoE terkini akun (B-ras Check)")
    fun session(@PathVariable id: UUID): BrasSessionView = sessions.session(id)

    @GetMapping("/access/{id}/traffic")
    @PreAuthorize("@authz.can('bng.session.view')")
    @Operation(summary = "Tren trafik akun (Down/Up Mbps) beberapa jam ke belakang")
    fun traffic(@PathVariable id: UUID, @RequestParam(defaultValue = "24") hours: Int): TrafficHistoryView =
        sessions.traffic(id, hours)
}

data class SaveNasRequest(
    @field:NotBlank val name: String,
    val vendor: NasVendor,
    val address: String?,
    val nasIdentifier: String?,
    /** Kosong saat update = biarkan secret apa adanya. */
    val coaSecret: String?,
    val collectorId: UUID?,
    val enabled: Boolean = true,
    /** Kredensial kontrol adapter nyata (7d): REST RouterOS / SQL FreeRADIUS. */
    val apiUsername: String? = null,
    /** Kosong saat update = biarkan password kontrol apa adanya. */
    val apiSecret: String? = null,
    val apiPort: Int? = null,
    val apiUseTls: Boolean = true,
    val apiDatabase: String? = null,
) {
    fun toCommand() = SaveNasCommand(
        name, vendor, address, nasIdentifier, coaSecret, collectorId, enabled,
        apiUsername, apiSecret, apiPort, apiUseTls, apiDatabase,
    )
}

data class ProvisionAccessRequest(
    val subscriptionId: UUID,
    @field:NotBlank val username: String,
    @field:NotBlank val secret: String,
    val planId: UUID,
    val nasId: UUID?,
) {
    fun toCommand() = ProvisionAccessCommand(subscriptionId, username, secret, planId, nasId)
}

data class UpdateAccessRequest(
    val planId: UUID,
    val nasId: UUID?,
) {
    fun toCommand() = UpdateAccessCommand(planId, nasId)
}

data class ResetSecretRequest(
    @field:NotBlank val secret: String,
) {
    fun toCommand() = ResetSecretCommand(secret)
}

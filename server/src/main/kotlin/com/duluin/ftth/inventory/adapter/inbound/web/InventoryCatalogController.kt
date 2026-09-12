package com.duluin.ftth.inventory.adapter.inbound.web

import com.duluin.ftth.common.security.CurrentUserProvider
import com.duluin.ftth.inventory.application.service.*
import com.duluin.ftth.inventory.domain.model.*
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.Positive
import org.springframework.http.HttpStatus
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.*
import java.math.BigDecimal
import java.util.UUID

/**
 * Master data gudang: lokasi, item, dan pendaftaran aset serial massal.
 *
 * Path item SENGAJA `/api/inventory/item-master`, bukan `/api/inventory/items`: yang terakhir
 * sudah dipakai [InventoryQueryController] untuk mengembalikan daftar ASET SERIAL dan sudah
 * dikonsumsi `web/src/api/inventory.ts`. Menumpuk arti kedua di path yang sama akan
 * mematikan layar yang sudah berjalan tanpa satu pun error yang menjelaskan kenapa.
 */
@RestController
@RequestMapping("/api/inventory")
class InventoryCatalogController(
    private val items: InventoryItemService,
    private val locations: InventoryLocationService,
    private val registration: InventoryAssetRegistrationService,
    private val currentUser: CurrentUserProvider,
) {
    @GetMapping("/locations")
    @PreAuthorize("@authz.can('inventory.location.view')")
    fun locations(@RequestParam(required = false) kind: LocationKind?): List<LocationView> =
        locations.list(currentUser.current().tenantId, kind).map { LocationView.of(it) }

    @PostMapping("/locations")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@authz.can('inventory.location.manage')")
    fun createLocation(@Valid @RequestBody body: LocationBody): LocationView {
        val tenantId = currentUser.current().tenantId
        return LocationView.of(locations.create(CreateInventoryLocation(tenantId, body.code, body.kind, body.parentId)))
    }

    @PutMapping("/locations/{id}")
    @PreAuthorize("@authz.can('inventory.location.manage')")
    fun updateLocation(@PathVariable id: UUID, @Valid @RequestBody body: UpdateLocationBody): LocationView {
        val tenantId = currentUser.current().tenantId
        return LocationView.of(locations.update(id, tenantId, UpdateInventoryLocation(body.code, body.parentId)))
    }

    @GetMapping("/item-master")
    @PreAuthorize("@authz.can('inventory.item.view')")
    fun items(@RequestParam(defaultValue = "false") includeInactive: Boolean): List<InventoryItemMasterView> =
        items.list(currentUser.current().tenantId, includeInactive).map { InventoryItemMasterView.of(it) }

    @GetMapping("/item-master/{id}")
    @PreAuthorize("@authz.can('inventory.item.view')")
    fun item(@PathVariable id: UUID): InventoryItemMasterView =
        InventoryItemMasterView.of(items.get(id, currentUser.current().tenantId))

    @PostMapping("/item-master")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@authz.can('inventory.item.manage')")
    fun createItem(@Valid @RequestBody body: CreateItemBody): InventoryItemMasterView {
        val tenantId = currentUser.current().tenantId
        return InventoryItemMasterView.of(items.create(body.toCommand(tenantId)))
    }

    @PutMapping("/item-master/{id}")
    @PreAuthorize("@authz.can('inventory.item.manage')")
    fun updateItem(@PathVariable id: UUID, @Valid @RequestBody body: UpdateItemBody): InventoryItemMasterView {
        val tenantId = currentUser.current().tenantId
        return InventoryItemMasterView.of(
            items.update(id, tenantId, UpdateInventoryItem(body.name, body.category, body.reorderPoint, body.trackMac)),
        )
    }

    /**
     * Nonaktifkan/aktifkan, BUKAN hapus — ledger dan aset lama tetap menunjuk id item ini.
     * DELETE sengaja tidak disediakan supaya tidak ada yang mengira riwayatnya ikut hilang.
     */
    @PostMapping("/item-master/{id}/active")
    @PreAuthorize("@authz.can('inventory.item.manage')")
    fun setItemActive(@PathVariable id: UUID, @RequestBody body: SetActiveBody): InventoryItemMasterView =
        InventoryItemMasterView.of(items.setActive(id, currentUser.current().tenantId, body.active))

    /** Tempel/scan daftar SN+MAC sekaligus. Satu serial bermasalah membatalkan seluruh daftar. */
    @PostMapping("/serialized/bulk")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@authz.can('inventory.item.manage')")
    fun registerSerials(@Valid @RequestBody body: BulkSerialBody): BulkSerialRegistrationResult {
        val actor = currentUser.current()
        return registration.registerBulk(body.toCommand(actor.tenantId, actor.userId))
    }
}

data class LocationBody(
    @field:NotBlank val code: String,
    val kind: LocationKind,
    val parentId: UUID? = null,
)

data class UpdateLocationBody(@field:NotBlank val code: String, val parentId: UUID? = null)

data class LocationView(val id: UUID, val code: String, val kind: LocationKind, val parentId: UUID?) {
    companion object {
        fun of(location: InventoryLocation) = LocationView(location.id, location.code, location.kind, location.parentId)
    }
}

data class CreateItemBody(
    @field:NotBlank val code: String,
    @field:NotBlank val name: String,
    val category: InventoryItemCategory,
    val unit: InventoryUnit,
    val serialized: Boolean,
    val trackMac: Boolean = false,
    val reorderPoint: BigDecimal? = null,
) {
    fun toCommand(tenantId: UUID) = CreateInventoryItem(tenantId, code, name, category, unit, serialized, trackMac, reorderPoint)
}

data class UpdateItemBody(
    @field:NotBlank val name: String,
    val category: InventoryItemCategory,
    val reorderPoint: BigDecimal? = null,
    val trackMac: Boolean = false,
)

data class SetActiveBody(val active: Boolean)

data class InventoryItemMasterView(
    val id: UUID,
    val code: String,
    val name: String,
    val category: InventoryItemCategory,
    val unit: InventoryUnit,
    val serialized: Boolean,
    val trackMac: Boolean,
    val reorderPoint: BigDecimal?,
    val active: Boolean,
) {
    companion object {
        fun of(item: InventoryItem) = InventoryItemMasterView(
            item.id, item.code, item.name, item.category, item.unit,
            item.serialized, item.trackMac, item.reorderPoint, item.active,
        )
    }
}

data class BulkSerialBody(
    val itemId: UUID,
    val locationId: UUID,
    val custodianId: UUID,
    @field:NotEmpty val serials: List<SerialLineBody>,
    @field:NotBlank val reason: String,
    @field:NotBlank val operationKey: String,
    @field:NotBlank val payloadHash: String,
) {
    fun toCommand(tenantId: UUID, actorId: UUID) = BulkSerialRegistration(
        tenantId, actorId, itemId, locationId, custodianId,
        serials.map { SerialRegistrationLine(it.serialNumber, it.macAddress) },
        reason, operationKey, payloadHash,
    )
}

data class SerialLineBody(@field:NotBlank val serialNumber: String, val macAddress: String? = null)

data class StockLineBody(
    val itemId: UUID,
    @field:Positive val quantity: Int,
    val serialNumbers: List<String> = emptyList(),
) {
    fun toLine() = StockLine(itemId, quantity, serialNumbers)
}

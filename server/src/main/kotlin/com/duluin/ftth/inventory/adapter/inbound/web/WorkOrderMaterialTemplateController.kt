package com.duluin.ftth.inventory.adapter.inbound.web

import com.duluin.ftth.common.security.CurrentUserProvider
import com.duluin.ftth.inventory.InventoryAllocationApi
import com.duluin.ftth.inventory.MaterialTemplateLineInput
import com.duluin.ftth.inventory.SaveMaterialTemplateCommand
import com.duluin.ftth.inventory.WorkOrderMaterialTemplateView
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.Positive
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * BOM: material standar per jenis work order.
 *
 * Ditempatkan di `/api/inventory` dan memakai izin `inventory.item.*`, BUKAN
 * `workorder.material.*`, karena ini master data gudang — yang menyusunnya petugas master
 * data, bukan teknisi yang mengerjakan WO. Teknisi hanya MEMBACA template lewat
 * `/api/work-orders/{id}/materials/template`.
 *
 * Template ini pra-isi, bukan pagar. Lihat komentar panjang di V186: rencana yang menyimpang
 * dari template tetap diterima, selisihnya yang wajib terlihat.
 */
@RestController
@RequestMapping("/api/inventory/material-templates")
@Tag(name = "Inventory")
@SecurityRequirement(name = "bearer-jwt")
class WorkOrderMaterialTemplateController(
    private val allocations: InventoryAllocationApi,
    private val currentUser: CurrentUserProvider,
) {
    @GetMapping("/{workOrderType}")
    @PreAuthorize("@authz.can('inventory.item.view')")
    fun template(@PathVariable workOrderType: String): List<WorkOrderMaterialTemplateView> =
        allocations.materialTemplate(currentUser.current().tenantId, workOrderType)

    /**
     * Ganti SELURUH template jenis ini sekaligus. Sunting per baris akan membuat item yang
     * dicoret dari layar tidak pernah punya request "hapus" — ia hanya hilang dari daftar
     * yang dikirim lalu tetap hidup dan terus mempra-isi rencana dengan barang usang.
     */
    @PutMapping("/{workOrderType}")
    @PreAuthorize("@authz.can('inventory.item.manage')")
    fun save(
        @PathVariable workOrderType: String,
        @Valid @RequestBody body: MaterialTemplateBody,
    ): List<WorkOrderMaterialTemplateView> = allocations.saveMaterialTemplate(
        SaveMaterialTemplateCommand(
            currentUser.current().tenantId,
            workOrderType,
            body.lines.map { MaterialTemplateLineInput(it.itemId, it.plannedQuantity, it.note) },
        ),
    )
}

data class MaterialTemplateLineBody(
    val itemId: UUID,
    @field:Positive val plannedQuantity: Int,
    val note: String? = null,
)

data class MaterialTemplateBody(val lines: List<MaterialTemplateLineBody> = emptyList())

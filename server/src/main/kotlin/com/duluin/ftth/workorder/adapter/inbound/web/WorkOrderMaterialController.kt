package com.duluin.ftth.workorder.adapter.inbound.web

import com.duluin.ftth.inventory.IssuedMaterialLineInput
import com.duluin.ftth.inventory.PlannedMaterialLineInput
import com.duluin.ftth.inventory.WorkOrderMaterialTemplateView
import com.duluin.ftth.inventory.WorkOrderMaterialView
import com.duluin.ftth.inventory.WorkOrderRecoveredAssetView
import com.duluin.ftth.inventory.WorkOrderVanLocationView
import com.duluin.ftth.workorder.application.port.inbound.ManageWorkOrderMaterialUseCase
import com.duluin.ftth.workorder.application.port.inbound.WorkOrderAssetRecoveryCancelRequest
import com.duluin.ftth.workorder.application.port.inbound.WorkOrderAssetRecoveryRequest
import com.duluin.ftth.workorder.application.port.inbound.WorkOrderMaterialIssueRequest
import com.duluin.ftth.workorder.application.port.inbound.WorkOrderMaterialScanRequest
import com.duluin.ftth.workorder.application.port.inbound.WorkOrderMaterialUsageRequest
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.PositiveOrZero
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * Material yang dibawa teknisi untuk sebuah work order: rencana, pengeluaran dari gudang,
 * pencatatan pemakaian, dan scan nomor seri.
 *
 * Izinnya SENGAJA dipisah dari `inventory.*` karena aktornya berbeda. Teknisi lapangan boleh
 * MENCATAT pemakaian di WO-nya sendiri (`workorder.material.record`) tanpa boleh menyentuh
 * gudang sama sekali, sedangkan yang MENGELUARKAN barangnya adalah petugas gudang
 * (`workorder.material.issue`). Menyatukan keduanya berarti siapa pun yang boleh melapor
 * "kabelnya habis" juga boleh mengeluarkan kabel dari rak.
 */
@RestController
@RequestMapping("/api/work-orders/{id}/materials")
@Tag(name = "Work Order Material")
@SecurityRequirement(name = "bearer-jwt")
class WorkOrderMaterialController(private val materials: ManageWorkOrderMaterialUseCase) {

    @GetMapping
    @PreAuthorize("@authz.can('workorder.material.view')")
    fun list(@PathVariable id: UUID): List<WorkOrderMaterialView> = materials.materials(id)

    /** BOM jenis WO ini. Klien memakainya untuk mempra-isi layar rencana sebelum menyimpan. */
    @GetMapping("/template")
    @PreAuthorize("@authz.can('workorder.material.view')")
    fun template(@PathVariable id: UUID): List<WorkOrderMaterialTemplateView> = materials.materialTemplate(id)

    /**
     * Simpan rencana material. Body kosong (`{"lines": []}`) berarti "pakai BOM apa adanya" —
     * itulah jalur pra-isi yang dipakai dispatcher saat membuka WO baru.
     */
    @PutMapping
    @PreAuthorize("@authz.can('workorder.material.record')")
    fun plan(@PathVariable id: UUID, @Valid @RequestBody body: PlanMaterialBody): List<WorkOrderMaterialView> =
        materials.planMaterial(
            id,
            body.lines.map { PlannedMaterialLineInput(it.itemId, it.quantity) },
            body.clear,
        )

    /**
     * Keluarkan barang gudang ke van stock teknisi.
     *
     * `operationKey` + `payloadHash` WAJIB dari klien: petugas gudang bekerja di jaringan yang
     * putus-nyambung dan akan menekan tombol dua kali setiap kali layarnya diam. Tanpa kunci
     * operasi, tekanan kedua menjadi pengeluaran barang kedua.
     */
    @PostMapping("/issues")
    @PreAuthorize("@authz.can('workorder.material.issue')")
    fun issue(@PathVariable id: UUID, @Valid @RequestBody body: IssueMaterialBody): List<WorkOrderMaterialView> =
        materials.issueMaterial(id, body.toRequest())

    /** Pemakaian barang CURAH. Barang berserial ditolak di sini — jumlahnya datang dari scan. */
    @PostMapping("/usage")
    @PreAuthorize("@authz.can('workorder.material.record')")
    fun record(@PathVariable id: UUID, @Valid @RequestBody body: MaterialUsageBody): WorkOrderMaterialView =
        materials.recordMaterialUsage(
            id,
            WorkOrderMaterialUsageRequest(
                body.itemId, body.usedQuantity, body.returnedQuantity, body.lostQuantity, body.varianceReason,
            ),
        )

    /**
     * Scan satu unit berserial. Inilah satu-satunya cara unit berserial tercatat terpakai,
     * dan tanpa scan yang lengkap WO-nya TIDAK BISA diselesaikan.
     */
    @PostMapping("/serials")
    @PreAuthorize("@authz.can('workorder.material.record')")
    fun scan(@PathVariable id: UUID, @Valid @RequestBody body: MaterialSerialBody): WorkOrderMaterialView =
        materials.scanMaterialSerial(id, WorkOrderMaterialScanRequest(body.serialNumber, body.outcome, body.macAddress))

    // ------------------------------------------------- Penarikan aset (P2.6)

    /*
     * Izinnya SENGAJA memakai `workorder.material.record`/`.view` yang SUDAH ADA, bukan izin baru.
     *
     * Aktornya identik dengan scan material: teknisi yang sedang berdiri di rumah pelanggan.
     * Izin baru berarti seed peran baru, dan setiap peran lama yang belum diperbarui akan
     * menghasilkan teknisi yang boleh mencatat pemakaian tapi ditolak saat mencatat ONT yang dia
     * cabut — dan ONT itu tetap dibawa pulang, hanya tanpa jejak di pembukuan. Yang menjaga
     * penarikan bukan izinnya, melainkan persetujuan WO-nya (D7) dan penjaga di service (D6).
     */

    @GetMapping("/recovered-assets")
    @PreAuthorize("@authz.can('workorder.material.view')")
    fun recoveredAssets(@PathVariable id: UUID): List<WorkOrderRecoveredAssetView> = materials.recoveredAssets(id)

    /**
     * Van yang boleh dipilih sebagai `technicianLocationId` di `POST .../recovered-assets`.
     * Hanya `id` dan `code` — tidak ada atribut gudang lain yang ikut.
     *
     * **Kenapa terpisah dari `GET /api/inventory/locations`?** Karena aktornya teknisi lapangan,
     * dan daftar itu dijaga `inventory.location.view`. Izin tersebut membuka SELURUH master data
     * lokasi gudang: gudang, bin di dalamnya beserta hubungan induk-anaknya, lokasi karantina,
     * dan permukaan yang sama dipakai layar pengaturan gudang. Memberikannya kepada setiap
     * teknisi hanya supaya ia bisa menjawab "van mana yang boleh kupilih" adalah menukar satu
     * bidang isian dengan akses baca ke tata letak gudang perusahaan. Endpoint ini memulangkan
     * PERSIS yang dibutuhkan layar penarikan dan tidak satu bidang pun lebih, jadi izin material
     * WO yang sudah dipegang teknisi cukup untuk membukanya.
     *
     * Tanpa endpoint ini fitur penarikan aset praktis mati: `technicianLocationId` wajib dikirim,
     * dan satu-satunya cara menemukannya tertutup bagi orang yang justru mencabut ONT-nya.
     *
     * **Kenapa `canAny` menyertakan `workorder.material.record` yang notabene izin TULIS untuk
     * menjaga sebuah PEMBACAAN?** Karena `workorder.material.view` saja tidak cukup: teknisi
     * lapangan lazim hanya memegang `.record`, dan itu tepat orang yang butuh daftar ini. Yang
     * dibayar: di repo ini `AccessChecker.isWrite(code) = !code.endsWith(".view")`, dan izin
     * tulis melewati `assertNotLocked` — jadi pemanggil yang HANYA memegang `.record` menerima
     * **402** (bukan 403) saat langganan tenantnya menunggak. Jebakan itu tercatat di
     * `docs/serah-terima-gudang-pesanan.md` §4 dan di sini diterima DENGAN SENGAJA: satu-satunya
     * tujuan daftar ini adalah mengisi `POST .../recovered-assets`, yang dijaga
     * `workorder.material.record` dan karenanya SUDAH menolak tenant terkunci dengan 402 yang
     * sama. Teknisi yang tertahan di sini tidak kehilangan apa pun yang seharusnya bisa ia
     * kerjakan — tidak ada satu pun mode gagal baru yang lahir dari pilihan ini. (Pemegang
     * `.view` tidak terpengaruh sama sekali: `canAny` melewati `assertNotLocked` begitu salah
     * satu izin yang DIMILIKI berupa izin baca.)
     */
    @GetMapping("/van-locations")
    @PreAuthorize("@authz.canAny('workorder.material.view', 'workorder.material.record')")
    fun vanLocations(@PathVariable id: UUID): List<WorkOrderVanLocationView> = materials.vanLocations(id)

    /** Scan unit yang dicabut dari rumah pelanggan. Saldo baru bergerak saat WO disetujui. */
    @PostMapping("/recovered-assets")
    @PreAuthorize("@authz.can('workorder.material.record')")
    fun recoverAsset(@PathVariable id: UUID, @Valid @RequestBody body: RecoverAssetBody): WorkOrderRecoveredAssetView =
        materials.recoverAsset(
            id,
            WorkOrderAssetRecoveryRequest(
                body.serialNumber, body.technicianId, body.technicianLocationId, body.condition, body.note,
            ),
        )

    /**
     * Batalkan satu baris penarikan (salah scan).
     *
     * POST, bukan DELETE: barisnya TIDAK dihapus. Jejak "pernah tercatat ditarik lalu dibatalkan,
     * oleh siapa, dengan alasan apa" justru bagian yang paling perlu dibaca saat menyelisik
     * selisih stok. DELETE akan membuat pembaca mengira barisnya lenyap.
     */
    @PostMapping("/recovered-assets/{recoveredId}/cancel")
    @PreAuthorize("@authz.can('workorder.material.record')")
    fun cancelRecoveredAsset(
        @PathVariable id: UUID,
        @PathVariable recoveredId: UUID,
        @RequestBody(required = false) body: CancelRecoveredAssetBody?,
    ): WorkOrderRecoveredAssetView =
        materials.cancelRecoveredAsset(id, recoveredId, WorkOrderAssetRecoveryCancelRequest(body?.reason))
}

data class PlanMaterialLineBody(val itemId: UUID, @field:PositiveOrZero val quantity: Int)

data class PlanMaterialBody(
    val lines: List<PlanMaterialLineBody> = emptyList(),
    /**
     * `true` = kosongkan rencana; `lines` diabaikan. Bidang tersendiri, BUKAN disimpulkan dari
     * `lines` kosong — bentuk itu sudah berarti "pakai BOM apa adanya" sejak awal.
     */
    val clear: Boolean = false,
)

data class IssueMaterialLineBody(
    val itemId: UUID,
    @field:Positive val quantity: Int,
    val serialNumbers: List<String> = emptyList(),
)

data class IssueMaterialBody(
    val fromLocationId: UUID,
    val custodianId: UUID,
    val technicianId: UUID,
    val technicianLocationId: UUID,
    @field:NotEmpty val lines: List<IssueMaterialLineBody>,
    @field:NotBlank val reason: String,
    @field:NotBlank val operationKey: String,
    @field:NotBlank val payloadHash: String,
) {
    fun toRequest() = WorkOrderMaterialIssueRequest(
        fromLocationId, custodianId, technicianId, technicianLocationId,
        lines.map { IssuedMaterialLineInput(it.itemId, it.quantity, it.serialNumbers) },
        reason, operationKey, payloadHash,
    )
}

data class MaterialUsageBody(
    val itemId: UUID,
    @field:PositiveOrZero val usedQuantity: Int,
    @field:PositiveOrZero val returnedQuantity: Int = 0,
    @field:PositiveOrZero val lostQuantity: Int = 0,
    val varianceReason: String? = null,
)

data class MaterialSerialBody(
    @field:NotBlank val serialNumber: String,
    @field:NotBlank val outcome: String,
    val macAddress: String? = null,
)

data class RecoverAssetBody(
    @field:NotBlank val serialNumber: String,
    val technicianId: UUID,
    val technicianLocationId: UUID,
    /** GOOD / DAMAGED. Default GOOD supaya klien lama tidak perlu tahu bidang ini. */
    @field:NotBlank val condition: String = "GOOD",
    val note: String? = null,
)

data class CancelRecoveredAssetBody(val reason: String? = null)

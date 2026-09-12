package com.duluin.ftth.inventory.application.service

import com.duluin.ftth.common.domain.UuidV7
import com.duluin.ftth.common.domain.error.ConflictException
import com.duluin.ftth.common.domain.error.NotFoundException
import com.duluin.ftth.common.domain.error.ValidationException
import com.duluin.ftth.inventory.CancelWorkOrderRecoveredAssetCommand
import com.duluin.ftth.inventory.InventoryAllocationApi
import com.duluin.ftth.inventory.InventoryFulfillmentAllocation
import com.duluin.ftth.inventory.IssueWorkOrderMaterialCommand
import com.duluin.ftth.inventory.MaterialTemplateLineInput
import com.duluin.ftth.inventory.PlanWorkOrderMaterialCommand
import com.duluin.ftth.inventory.PlannedMaterialLineInput
import com.duluin.ftth.inventory.RecordWorkOrderMaterialUsageCommand
import com.duluin.ftth.inventory.RecoverWorkOrderAssetCommand
import com.duluin.ftth.inventory.SaveMaterialTemplateCommand
import com.duluin.ftth.inventory.ScanWorkOrderMaterialSerialCommand
import com.duluin.ftth.inventory.WorkOrderMaterialSerialView
import com.duluin.ftth.inventory.WorkOrderMaterialTemplateView
import com.duluin.ftth.inventory.WorkOrderMaterialView
import com.duluin.ftth.inventory.WorkOrderRecoveredAssetView
import com.duluin.ftth.inventory.application.port.outbound.InventoryItemRepository
import com.duluin.ftth.inventory.application.port.outbound.SerializedAssetRepository
import com.duluin.ftth.inventory.application.port.outbound.WorkOrderMaterialRepository
import com.duluin.ftth.inventory.application.port.outbound.WorkOrderMaterialTemplateRepository
import com.duluin.ftth.inventory.domain.model.InventoryItem
import com.duluin.ftth.inventory.domain.model.InventoryStatus
import com.duluin.ftth.inventory.domain.model.MaterialOutcome
import com.duluin.ftth.inventory.domain.model.SerializedAsset
import com.duluin.ftth.inventory.domain.model.WorkOrderMaterialLine
import com.duluin.ftth.inventory.domain.model.WorkOrderMaterialSerial
import com.duluin.ftth.inventory.domain.model.WorkOrderMaterialTemplateLine
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * Jembatan antara work order dan gudang: rencana material, pengeluaran barang ke teknisi,
 * pencatatan pemakaian, dan scan nomor seri.
 *
 * Kelas ini hidup di modul `inventory`, bukan `workorder`, DENGAN SENGAJA. Yang dijaga di
 * sini adalah saldo gudang, dan saldo hanya boleh bergerak lewat
 * [InventoryOperationsService] -> [InventoryMovementLedgerService]. Kalau tabel material
 * dimiliki `workorder`, modul itu harus mengimpor ledger untuk memotong stok — dan begitu
 * ada dua jalan masuk ke ledger, salah satunya cepat atau lambat lupa memvalidasi saldo
 * atau lupa menulis leg. `ModularityTests` juga menolak arah impor itu.
 */
@Service
class WorkOrderMaterialService(
    private val materials: WorkOrderMaterialRepository,
    private val templates: WorkOrderMaterialTemplateRepository,
    private val items: InventoryItemRepository,
    private val itemService: InventoryItemService,
    private val assets: SerializedAssetRepository,
    private val operations: InventoryOperationsService,
    /*
     * Penarikan aset WO DISMANTLE (celah "P2.6") punya tabelnya sendiri dan service-nya sendiri,
     * tapi PINTU MASUKNYA tetap satu: [InventoryAllocationApi]. Modul `workorder` tidak boleh
     * kenal dua bean gudang untuk satu persetujuan WO yang sama — begitu ia memanggil dua sumber
     * alokasi, ada urutan pemanggilan di mana material terpotong tapi tarikan tidak, dan tidak
     * satu pun checkpoint tahu bahwa persetujuan itu baru separuh jadi.
     */
    private val recovery: WorkOrderAssetRecoveryService,
    private val clock: Clock = Clock.systemUTC(),
) : InventoryAllocationApi {

    // ------------------------------------------------------------------ BOM

    @Transactional(readOnly = true)
    override fun materialTemplate(tenantId: UUID, workOrderType: String): List<WorkOrderMaterialTemplateView> {
        val type = normalizeType(workOrderType)
        val rows = templates.findByType(tenantId, type)
        val catalog = items.findAll(tenantId).associateBy { it.id }
        return rows.mapNotNull { row -> catalog[row.itemId]?.let { row.toView(it) } }
    }

    @Transactional
    override fun saveMaterialTemplate(command: SaveMaterialTemplateCommand): List<WorkOrderMaterialTemplateView> {
        val type = normalizeType(command.workOrderType)
        if (command.lines.map { it.itemId }.toSet().size != command.lines.size) {
            throw ValidationException("Satu item hanya boleh muncul sekali dalam template")
        }
        val resolved = command.lines.map { line -> itemService.requireActive(line.itemId, command.tenantId) to line }
        val saved = templates.replaceType(
            command.tenantId, type,
            resolved.map { (item, line) -> line.toTemplate(command.tenantId, type, item) },
        )
        val catalog = resolved.associate { (item, _) -> item.id to item }
        return saved.mapNotNull { row -> catalog[row.itemId]?.let { row.toView(it) } }
    }

    // -------------------------------------------------------------- Rencana

    @Transactional(readOnly = true)
    override fun materials(tenantId: UUID, workOrderId: UUID): List<WorkOrderMaterialView> {
        val lines = materials.findByWorkOrder(tenantId, workOrderId)
        return lines.map { it.toView(itemOf(tenantId, it.itemId)) }
    }

    /**
     * Susun ulang rencana material WO.
     *
     * Bentuknya GANTI-SELURUHNYA, bukan tambal per baris: rencana adalah satu daftar yang
     * dilihat teknisi, dan sunting per baris berarti item yang dicoret dari layar tidak
     * pernah punya request "hapus" — ia hanya hilang dari daftar yang dikirim lalu tetap
     * hidup di basis data dan terus dianggap harus dibawa.
     *
     * Baris yang barangnya SUDAH keluar gudang tidak bisa dicoret: barangnya nyata, ada di
     * tangan teknisi, dan harus tetap dipertanggungjawabkan.
     */
    @Transactional
    override fun planMaterial(command: PlanWorkOrderMaterialCommand): List<WorkOrderMaterialView> {
        val type = normalizeType(command.workOrderType)
        val tenantId = command.tenantId
        val template = templates.findByType(tenantId, type).associateBy { it.itemId }
        // Daftar kosong = "pakai BOM apa adanya". Inilah bentuk pra-isi yang dijanjikan:
        // dispatcher menekan "buat rencana" dan langsung mendapat daftar standar jenis WO-nya.
        val requested = command.lines.ifEmpty {
            template.values.map { PlannedMaterialLineInput(it.itemId, it.plannedQuantity) }
        }
        if (requested.map { it.itemId }.toSet().size != requested.size) {
            throw ValidationException("Satu item hanya boleh muncul sekali dalam rencana material")
        }

        val existing = materials.findByWorkOrder(tenantId, command.workOrderId).associateBy { it.itemId }
        val requestedIds = requested.map { it.itemId }.toSet()
        existing.values.filter { it.itemId !in requestedIds }.forEach { orphan ->
            if (orphan.issuedQuantity > 0) {
                throw ConflictException(
                    "Item ${itemOf(tenantId, orphan.itemId).code} sudah dikeluarkan gudang dan tidak bisa dicoret dari rencana",
                )
            }
            materials.deleteLine(tenantId, orphan.id)
        }

        return requested.map { input ->
            val item = itemService.requireActive(input.itemId, tenantId)
            val current = existing[input.itemId]
            val line = current?.replan(input.quantity, template[input.itemId]?.plannedQuantity)
                ?: WorkOrderMaterialLine.plan(
                    tenantId, command.workOrderId, type, item, command.customerId,
                    input.quantity, template[input.itemId]?.plannedQuantity,
                )
            materials.save(line).toView(item)
        }
    }

    // ----------------------------------------------------- Keluar dari gudang

    /**
     * Keluarkan material WO dari gudang ke van stock teknisi.
     *
     * Mutasi ISSUE dijalankan LEBIH DULU, baru `issued_quantity` dinaikkan. Urutan itu
     * penting: kalau angka WO naik duluan lalu mutasinya ditolak karena stok kurang, WO akan
     * mengaku memegang barang yang masih di rak, dan saat approval saga akan memotong saldo
     * yang tidak pernah ada.
     *
     * Kegagalan [InventoryOperationsService.issue] SENGAJA tidak ditangkap. Ia bean
     * `@Transactional` tersendiri: menangkap exception-nya menandai transaksi bersama
     * rollback-only dan commit-nya akan meledak sebagai `UnexpectedRollbackException` yang
     * tidak bisa dibaca siapa pun.
     */
    @Transactional
    override fun issueMaterial(command: IssueWorkOrderMaterialCommand): List<WorkOrderMaterialView> {
        if (command.lines.isEmpty()) throw ValidationException("Tidak ada baris material yang dikeluarkan")
        // Item ganda dalam satu permintaan akan membuat baris kedua menimpa baris pertama
        // (keduanya berangkat dari snapshot yang sama), sehingga stok benar-benar keluar dua
        // kali tapi `issued_quantity` hanya naik sekali — selisihnya menguap dari pembukuan.
        if (command.lines.map { it.itemId }.toSet().size != command.lines.size) {
            throw ValidationException("Satu item hanya boleh muncul sekali dalam satu pengeluaran")
        }
        val tenantId = command.tenantId
        val planned = materials.findByWorkOrder(tenantId, command.workOrderId).associateBy { it.itemId }
        command.lines.forEach { line ->
            val current = planned[line.itemId]
                ?: throw ValidationException(
                    "Item ${itemOf(tenantId, line.itemId).code} belum ada di rencana material WO ini — tambahkan ke rencana dulu",
                )
            if (line.quantity <= 0) throw ValidationException("Jumlah pengeluaran harus lebih dari nol")
            if (current.issuedQuantity + line.quantity > current.plannedQuantity) {
                throw ConflictException(
                    "Item ${itemOf(tenantId, line.itemId).code}: pengeluaran melebihi rencana ${current.plannedQuantity} unit",
                )
            }
        }

        operations.issue(
            IssueCommand(
                tenantId = tenantId,
                actorId = command.actorId,
                fromLocationId = command.fromLocationId,
                custodianId = command.custodianId,
                technicianId = command.technicianId,
                technicianLocationId = command.technicianLocationId,
                lines = command.lines.map { StockLine(it.itemId, it.quantity, it.serialNumbers) },
                reason = command.reason,
                operationKey = command.operationKey,
                payloadHash = command.payloadHash,
            ),
        )

        return command.lines.map { line ->
            val item = itemOf(tenantId, line.itemId)
            val updated = planned.getValue(line.itemId)
                .markIssued(line.quantity, command.technicianId, command.technicianLocationId)
            materials.save(updated).toView(item)
        }
    }

    // ------------------------------------------------------------- Realisasi

    @Transactional
    override fun recordMaterialUsage(command: RecordWorkOrderMaterialUsageCommand): WorkOrderMaterialView {
        val line = materials.findByWorkOrderAndItem(command.tenantId, command.workOrderId, command.itemId)
            ?: throw NotFoundException("Material ini tidak ada di rencana work order")
        val updated = line.recordBulkUsage(
            command.usedQuantity, command.returnedQuantity, command.lostQuantity, command.varianceReason,
        )
        return materials.save(updated).toView(itemOf(command.tenantId, line.itemId))
    }

    /**
     * Scan satu unit berserial saat menyelesaikan WO.
     *
     * Tiga pemeriksaan di bawah bukan formalitas — ketiganya menjawab "apakah barang ini
     * BENAR-BENAR dari gudang kita dan BENAR-BENAR di tangan teknisi ini":
     *
     *  1. nomor serinya terdaftar sebagai aset tenant ini;
     *  2. jenis barangnya memang direncanakan di WO ini;
     *  3. statusnya ISSUED dan pemegangnya teknisi yang tercatat di baris material itu.
     *
     * Tanpa (3), ONT milik teknisi lain — atau ONT yang masih di rak gudang — bisa diklaim
     * terpasang di sini, dan pemotongan saldo saat approval akan mendarat di dimensi saldo
     * orang lain: satu orang kehilangan stok yang tidak pernah dia pegang, satu lagi
     * menyimpan stok hantu yang tidak pernah bisa diretur.
     */
    @Transactional
    override fun scanMaterialSerial(command: ScanWorkOrderMaterialSerialCommand): WorkOrderMaterialView {
        val tenantId = command.tenantId
        val serialNumber = command.serialNumber.trim()
        if (serialNumber.isEmpty()) throw ValidationException("Nomor seri wajib diisi")
        val outcome = parseOutcome(command.outcome)

        val asset = assets.findBySerial(tenantId, serialNumber)
            ?: throw NotFoundException("Nomor seri $serialNumber tidak terdaftar di gudang")
        val line = materials.findByWorkOrderAndItem(tenantId, command.workOrderId, asset.skuId)
            ?: throw ValidationException("Nomor seri $serialNumber bukan item yang direncanakan di work order ini")
        if (!line.serialized) throw ValidationException("Item ${itemOf(tenantId, line.itemId).code} bukan barang berserial")
        requireCustody(asset, line)

        if (outcome == MaterialOutcome.INSTALLED) {
            val clash = materials.findInstalledSerialByAsset(tenantId, asset.id)
            if (clash != null && clash.workOrderId != command.workOrderId) {
                throw ConflictException("Nomor seri $serialNumber sudah tercatat terpasang di work order lain")
            }
        }

        // Id baris serial dipertahankan kalau unit ini pernah di-scan di WO yang sama:
        // id itu dipakai sebagai `targetId` alokasi, yaitu bagian kunci idempotensi saga.
        // Membuat id baru untuk koreksi scan akan membuat saga memotong saldo dua kali.
        val priorId = line.serials.firstOrNull { it.assetId == asset.id }?.id
        val serial = WorkOrderMaterialSerial(
            id = priorId ?: UuidV7.generate(),
            tenantId = tenantId,
            materialId = line.id,
            workOrderId = command.workOrderId,
            assetId = asset.id,
            serialNumber = asset.serialNumber,
            // Aset adalah sumber kebenaran MAC; nilai dari klien hanya dipakai kalau aset
            // memang belum punya (mis. ONT yang MAC-nya baru terbaca saat dipasang).
            macAddress = asset.macAddress ?: command.macAddress?.trim()?.takeIf { it.isNotEmpty() },
            outcome = outcome,
            scannedAt = Instant.now(clock),
            scannedBy = command.actorId,
        )
        val updated = line.attachSerial(serial)
        materials.saveSerial(serial)
        return materials.save(updated).toView(itemOf(tenantId, line.itemId))
    }

    // ------------------------------------------------- Penarikan aset (P2.6)

    /*
     * Tiga jalur di bawah hanya meneruskan ke [WorkOrderAssetRecoveryService]. Logikanya SENGAJA
     * tidak ditulis di sini: penarikan bukan baris material (D1). Baris material berarti "diambil
     * DARI gudang untuk WO ini", sedangkan ONT yang dicabut dari rumah pelanggan tidak pernah
     * diambil dari gudang untuk WO ini. Memaksanya masuk ke tabel yang sama membuat
     * plannedQuantity/issuedQuantity kehilangan arti dan membuat [assertMaterialReadyForCompletion]
     * menuntut rencana untuk barang yang memang tidak punya rencana.
     */

    override fun recoverAsset(command: RecoverWorkOrderAssetCommand): WorkOrderRecoveredAssetView =
        recovery.recoverAsset(command)

    @Transactional(readOnly = true)
    override fun recoveredAssets(tenantId: UUID, workOrderId: UUID): List<WorkOrderRecoveredAssetView> =
        recovery.recoveredAssets(tenantId, workOrderId)

    override fun cancelRecoveredAsset(command: CancelWorkOrderRecoveredAssetCommand): WorkOrderRecoveredAssetView =
        recovery.cancelRecoveredAsset(command)

    // -------------------------------------------------------------- Penjaga

    @Transactional(readOnly = true)
    override fun assertMaterialReadyForCompletion(tenantId: UUID, workOrderId: UUID) {
        materials.findByWorkOrder(tenantId, workOrderId).forEach { line ->
            line.assertReadyForCompletion(itemOf(tenantId, line.itemId).code)
        }
    }

    @Transactional(readOnly = true)
    override fun hasFulfillableMaterial(tenantId: UUID, workOrderId: UUID): Boolean =
        allocationsFor(tenantId, workOrderId).isNotEmpty()

    /**
     * Alokasi yang akan dipotong saga fulfillment saat WO disetujui.
     *
     * Hanya nasib TERPASANG yang masuk. Unit yang dibawa pulang harus kembali lewat retur
     * gudang biasa (custody-nya berpindah, bukan lenyap), dan unit yang hilang harus lewat
     * penyesuaian yang wajib disetujui — memasukkan keduanya ke sini berarti "hilang di
     * lapangan" bisa dihapusbukukan tanpa mata kedua, dan justru itu bentuk kebocoran aset
     * yang paling sulit terlihat.
     *
     * `targetId` WAJIB unik per alokasi: saga menyambungnya jadi
     * `"${operationKey}:${targetId}"` sebagai kunci idempotensi per efek. Dua alokasi dengan
     * targetId sama akan dianggap replay satu sama lain dan salah satunya TIDAK PERNAH
     * memotong stok. Karena itu unit berserial memakai id baris serialnya (satu per unit
     * fisik) dan barang curah memakai id baris materialnya.
     */
    @Transactional(readOnly = true)
    fun allocationsFor(tenantId: UUID, workOrderId: UUID): List<InventoryFulfillmentAllocation> =
        consumptionAllocations(tenantId, workOrderId) +
            /*
             * Alokasi arah MASUK dari aset yang ditarik saat WO DISMANTLE ikut di daftar YANG SAMA.
             *
             * Digabung di sini, bukan dipanggil terpisah oleh modul `workorder`, supaya seluruh
             * hilir ikut benar dengan sendirinya: [hasFulfillableMaterial] jadi `true` untuk WO
             * DISMANTLE yang cuma menarik ONT tanpa memakai material apa pun (tanpa ini efek
             * INVENTORY-nya tidak pernah dijadwalkan dan tarikannya menguap), dan satu persetujuan
             * WO tetap menghasilkan satu preflight dengan satu skema idempotensi.
             */
            recovery.allocationsFor(tenantId, workOrderId)

    private fun consumptionAllocations(tenantId: UUID, workOrderId: UUID): List<InventoryFulfillmentAllocation> =
        materials.findByWorkOrder(tenantId, workOrderId).flatMap { line ->
            val locationId = line.technicianLocationId
            val technicianId = line.technicianId
            // Baris yang belum pernah keluar gudang tidak punya dimensi saldo untuk dipotong.
            if (locationId == null || technicianId == null) return@flatMap emptyList()
            if (line.serialized) {
                line.serials.filter { it.outcome == MaterialOutcome.INSTALLED }.map { serial ->
                    InventoryFulfillmentAllocation(
                        targetId = serial.id,
                        itemId = line.itemId,
                        skuId = line.itemId,
                        locationId = locationId,
                        customerId = line.customerId,
                        quantity = 1,
                        serialized = true,
                        actorId = technicianId,
                        itemCategory = line.itemCategory,
                        assetId = serial.assetId,
                        serialNumber = serial.serialNumber,
                    )
                }
            } else if (line.usedQuantity > 0) {
                listOf(
                    InventoryFulfillmentAllocation(
                        targetId = line.id,
                        itemId = line.itemId,
                        skuId = line.itemId,
                        locationId = locationId,
                        customerId = line.customerId,
                        quantity = line.usedQuantity,
                        serialized = false,
                        actorId = technicianId,
                        itemCategory = line.itemCategory,
                    ),
                )
            } else {
                emptyList()
            }
        }

    // -------------------------------------------------------------- Internal

    private fun requireCustody(asset: SerializedAsset, line: WorkOrderMaterialLine) {
        if (asset.status != InventoryStatus.ISSUED) {
            throw ConflictException("Nomor seri ${asset.serialNumber} berstatus ${asset.status}, bukan ISSUED — barang ini belum dikeluarkan gudang")
        }
        if (line.technicianId != null && asset.custody.ownerId != line.technicianId) {
            throw ConflictException("Nomor seri ${asset.serialNumber} dipegang teknisi lain")
        }
        if (line.technicianLocationId != null && asset.locationId != line.technicianLocationId) {
            throw ConflictException("Nomor seri ${asset.serialNumber} tidak berada di van stock teknisi work order ini")
        }
    }

    private fun itemOf(tenantId: UUID, itemId: UUID): InventoryItem =
        items.findById(itemId)?.takeIf { it.tenantId == tenantId }
            ?: throw NotFoundException("Item gudang tidak ditemukan")

    /**
     * Cermin dari CHECK `work_order_material_type_ck` di V186.
     *
     * Daftarnya DIULANG di sini, bukan diimpor dari `WorkOrderType`, karena modul
     * `inventory` DILARANG mengimpor `workorder` — impor itu melahirkan siklus modul dan
     * `ModularityTests` menolaknya. Yang dibayar: jenis WO baru harus ditambahkan di dua
     * tempat. Yang dihindari: siklus modul permanen demi satu enum.
     */
    private fun normalizeType(workOrderType: String): String {
        val type = workOrderType.trim().uppercase()
        if (type !in WORK_ORDER_TYPES) throw ValidationException("Jenis work order $workOrderType tidak dikenal")
        return type
    }

    private fun parseOutcome(raw: String): MaterialOutcome =
        MaterialOutcome.entries.firstOrNull { it.name == raw.trim().uppercase() }
            ?: throw ValidationException("Nasib material harus INSTALLED, RETURNED, atau LOST")

    private fun MaterialTemplateLineInput.toTemplate(tenantId: UUID, type: String, item: InventoryItem) =
        WorkOrderMaterialTemplateLine(UuidV7.generate(), tenantId, type, item.id, plannedQuantity, note?.trim()?.takeIf { it.isNotEmpty() })

    private fun WorkOrderMaterialTemplateLine.toView(item: InventoryItem) = WorkOrderMaterialTemplateView(
        itemId = itemId,
        itemCode = item.code,
        itemName = item.name,
        itemCategory = item.category.name,
        unit = item.unit.name,
        serialized = item.serialized,
        plannedQuantity = plannedQuantity,
        note = note,
    )

    private fun WorkOrderMaterialLine.toView(item: InventoryItem) = WorkOrderMaterialView(
        id = id,
        workOrderId = workOrderId,
        itemId = itemId,
        itemCode = item.code,
        itemName = item.name,
        itemCategory = itemCategory,
        unit = item.unit.name,
        serialized = serialized,
        templateQuantity = templateQuantity,
        plannedQuantity = plannedQuantity,
        issuedQuantity = issuedQuantity,
        usedQuantity = usedQuantity,
        returnedQuantity = returnedQuantity,
        lostQuantity = lostQuantity,
        unscannedQuantity = unscannedQuantity,
        technicianId = technicianId,
        technicianLocationId = technicianLocationId,
        varianceReason = varianceReason,
        serials = serials.map {
            WorkOrderMaterialSerialView(it.id, it.assetId, it.serialNumber, it.macAddress, it.outcome.name, it.scannedAt, it.scannedBy)
        },
    )

    private companion object {
        val WORK_ORDER_TYPES = setOf("PSB", "REPAIR", "MIGRATION", "DISMANTLE", "PREVENTIVE")
    }
}

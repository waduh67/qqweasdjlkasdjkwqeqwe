package com.duluin.ftth.inventory.application.service

import com.duluin.ftth.common.domain.UuidV7
import com.duluin.ftth.common.domain.error.ConflictException
import com.duluin.ftth.common.domain.error.NotFoundException
import com.duluin.ftth.common.domain.error.ValidationException
import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.iam.IamApi
import com.duluin.ftth.inventory.CancelWorkOrderRecoveredAssetCommand
import com.duluin.ftth.inventory.InventoryFulfillmentAllocation
import com.duluin.ftth.inventory.RecoverWorkOrderAssetCommand
import com.duluin.ftth.inventory.WorkOrderRecoveredAssetView
import com.duluin.ftth.inventory.application.port.outbound.InventoryItemRepository
import com.duluin.ftth.inventory.application.port.outbound.InventoryLocationRepository
import com.duluin.ftth.inventory.application.port.outbound.SerializedAssetRepository
import com.duluin.ftth.inventory.application.port.outbound.WorkOrderRecoveredAssetRepository
import com.duluin.ftth.inventory.domain.model.InventoryItem
import com.duluin.ftth.inventory.domain.model.InventoryStatus
import com.duluin.ftth.inventory.domain.model.WorkOrderRecoveredAsset
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * Penarikan aset pelanggan saat work order DISMANTLE (celah "P2.6").
 *
 * Sebelum ini, pembongkaran tidak punya jalan pulang: ONT-nya dicabut dari rumah pelanggan,
 * dibawa teknisi, dan secara pembukuan TETAP CONSUMED selamanya. `InventoryApi.returnFulfillment`
 * sudah ada sejak lama — lengkap dengan idempotensi, ledger, dan proyeksi saldonya — tapi NOL
 * pemanggil produksi. Kelas inilah yang menyambungnya.
 *
 * Hidup di modul `inventory`, bukan `workorder`, dengan alasan yang persis sama seperti
 * [WorkOrderMaterialService]: saldo gudang hanya boleh punya SATU jalan masuk. Begitu modul lain
 * boleh menulis ledger sendiri, salah satu jalan cepat atau lambat lupa memvalidasi sesuatu.
 *
 * TIDAK ADA tier approval gudang tersendiri di sini, dan itu keputusan sadar (D7): persetujuan
 * WO-nya sendiri adalah mata kedua. Saldo tidak bergerak sedikit pun sebelum WO disetujui, dan
 * penyetujunya bukan teknisi yang men-scan. Menumpuk antrean approval gudang di atasnya hanya
 * melahirkan antrean kedua yang tak menambah informasi apa pun — dan antrean seperti itu selalu
 * berakhir di-approve buta.
 */
@Service
class WorkOrderAssetRecoveryService(
    private val recovered: WorkOrderRecoveredAssetRepository,
    private val assets: SerializedAssetRepository,
    private val items: InventoryItemRepository,
    private val locations: InventoryLocationRepository,
    /* Hanya untuk menamai id orang di read model — lihat [WorkOrderPeopleNames]. */
    private val iam: IamApi,
    private val clock: Clock = Clock.systemUTC(),
) {

    /**
     * Scan satu unit yang dicabut dari rumah pelanggan.
     *
     * Empat penjaga di bawah semuanya wajib, dan masing-masing menutup satu cara menciptakan
     * stok hantu:
     *
     *  1. nomor serinya terdaftar sebagai aset tenant ini — SN karangan bukan barang;
     *  2. statusnya CONSUMED (D6a) — unit AVAILABLE di rak bukan unit yang terpasang di rumah
     *     siapa pun, dan "menariknya" berarti menambahkan saldo untuk barang yang saldonya sudah
     *     dihitung;
     *  3. belum pernah ditarik (D6b) — dijaga juga oleh indeks unik parsial di basis data;
     *  4. van stock teknisinya nyata dan milik tenant ini (D6c) — saldo harus mendarat di
     *     dimensi yang benar-benar ada.
     */
    @Transactional
    fun recoverAsset(command: RecoverWorkOrderAssetCommand): WorkOrderRecoveredAssetView {
        val tenantId = requireTenant(command.tenantId)
        val serialNumber = command.serialNumber.trim()
        if (serialNumber.isEmpty()) throw ValidationException("Nomor seri wajib diisi")
        val condition = WorkOrderRecoveredAsset.parseCondition(command.condition)

        val asset = assets.findBySerial(tenantId, serialNumber)
            ?: throw NotFoundException("Nomor seri $serialNumber tidak terdaftar di gudang")

        /*
         * (D6a) HANYA unit CONSUMED yang bisa ditarik.
         *
         * CONSUMED adalah satu-satunya status yang berarti "unit ini terpasang di rumah
         * pelanggan dan sudah keluar dari saldo". Setiap status lain berarti unitnya MASIH
         * terhitung di suatu dimensi saldo: AVAILABLE di rak, ISSUED di tas teknisi, RETURNED
         * menunggu diperiksa. Menerima salah satunya di sini berarti menambah satu unit ke saldo
         * untuk barang yang sudah dihitung — dan karena mutasinya terlihat sah, tidak ada satu
         * laporan pun yang bisa menunjuk mana yang palsu.
         */
        if (asset.status != InventoryStatus.CONSUMED) {
            throw ConflictException(
                "Nomor seri ${asset.serialNumber} berstatus ${asset.status}, bukan CONSUMED — " +
                    "unit ini tidak sedang terpasang di pelanggan mana pun",
            )
        }

        /*
         * (D6b) PENJAGA TERPENTING. Satu unit fisik ditarik dua kali = satu barang nyata yang
         * dihitung dua kali di pembukuan. Pemeriksaan ini memberi pesan yang bisa dibaca teknisi;
         * penjaga terakhirnya tetap indeks unik parsial `work_order_recovered_asset_active_uq`,
         * karena dua request bersamaan bisa sama-sama lolos di sini.
         */
        recovered.findActiveByAsset(tenantId, asset.id)?.let {
            throw ConflictException(
                "Nomor seri ${asset.serialNumber} sudah tercatat ditarik" +
                    if (it.workOrderId == command.workOrderId) "" else " di work order lain",
            )
        }

        val item = items.findById(asset.skuId)?.takeIf { it.tenantId == tenantId }
            ?: throw NotFoundException("Item gudang untuk nomor seri ${asset.serialNumber} tidak ditemukan")

        // (D6c) Van stock teknisi WAJIB nyata dan milik tenant ini. Lokasi karangan membuat leg
        // IN mendarat di dimensi saldo yang tidak pernah bisa dibaca layar mana pun, dan barang
        // yang benar-benar dibawa pulang itu hilang lagi dari pandangan.
        val holder = locations.findById(command.technicianLocationId)?.takeIf { it.tenantId == tenantId }
            ?: throw NotFoundException("Lokasi van stock teknisi tidak ditemukan")

        val row = WorkOrderRecoveredAsset(
            id = UuidV7.generate(),
            tenantId = tenantId,
            workOrderId = command.workOrderId,
            assetId = asset.id,
            serialNumber = asset.serialNumber,
            // Aset adalah sumber kebenaran MAC. Tidak ada nilai dari klien di sini: unit yang
            // ditarik sudah lama terdaftar, jadi MAC-nya sudah diketahui sejak dipasang.
            macAddress = asset.macAddress,
            itemId = item.id,
            itemCategory = item.category.name,
            customerId = command.customerId,
            technicianId = command.technicianId,
            technicianLocationId = holder.id,
            condition = condition,
            note = command.note?.trim()?.takeIf { it.isNotEmpty() },
            recoveredAt = Instant.now(clock),
            recoveredBy = command.actorId,
        )
        val saved = recovered.save(row)
        // Jalur TULIS pun bernama: tanpa ini layar teknisi berkedip dari nama ke UUID tepat
        // setelah unitnya di-scan. Kode vannya sudah di tangan — barisnya lahir dari [holder]
        // yang baru saja dibaca, jadi tidak ada query tambahan untuk menamainya.
        return saved.toView(item, peopleNames(listOf(saved)), mapOf(holder.id to holder.code))
    }

    @Transactional(readOnly = true)
    fun recoveredAssets(tenantId: UUID, workOrderId: UUID): List<WorkOrderRecoveredAssetView> {
        val active = requireTenant(tenantId)
        // Barisnya DULU, baru SATU panggilan direktori pengguna dan SATU pembacaan lokasi untuk
        // seluruh daftarnya.
        val rows = recovered.findByWorkOrder(active, workOrderId)
        val names = peopleNames(rows)
        val vans = locationCodes(active, rows)
        // Katalog barangnya juga SEKALI untuk seluruh daftar. Sebelumnya baris ini memanggil
        // `itemOf(...)` di dalam `map`, jadi satu WO dengan 20 unit tertarik membaca master
        // barang 20 kali hanya untuk menyusun satu respons.
        val catalog = itemsOf(active, rows)
        return rows.map { it.toView(catalog.getValue(it.itemId), names, vans) }
    }

    @Transactional
    fun cancelRecoveredAsset(command: CancelWorkOrderRecoveredAssetCommand): WorkOrderRecoveredAssetView {
        val tenantId = requireTenant(command.tenantId)
        val row = recovered.findById(tenantId, command.recoveredAssetId)
            ?: throw NotFoundException("Baris penarikan aset tidak ditemukan")
        if (row.workOrderId != command.workOrderId) {
            throw ValidationException("Baris penarikan ini bukan milik work order yang diminta")
        }
        val cancelled = row.cancel(Instant.now(clock), command.actorId, command.reason)
        val saved = recovered.save(cancelled)
        // Jalur tulis KEDUA, dan ia sama wajibnya dengan yang pertama: kalau pembatalan
        // memulangkan view tanpa kode van, kolom itu berkedip jadi UUID tepat setelah tombol
        // "batalkan" ditekan lalu benar lagi saat layarnya di-refresh.
        return saved.toView(
            itemOf(tenantId, row.itemId),
            peopleNames(listOf(saved)),
            locationCodes(tenantId, listOf(saved)),
        )
    }

    /**
     * Alokasi arah MASUK yang ikut dipotong saga saat WO disetujui.
     *
     * Baris yang sudah DIBATALKAN sengaja tidak ikut: pembatalan hanya berarti sesuatu kalau ia
     * benar-benar mencegah saldo bergerak. Kalau baris batal tetap dipancarkan, "batal" cuma jadi
     * catatan kosmetik di layar sementara stoknya tetap bertambah.
     *
     * `targetId` = id BARIS PENARIKAN, bukan id asetnya. Saga menyambungnya jadi
     * `"${operationKey}:${targetId}"` sebagai kunci idempotensi per efek, dan id baris itu unik
     * per unit fisik yang ditarik. Memakai id aset akan bentrok dengan alokasi lain yang kebetulan
     * menyangkut unit yang sama.
     */
    @Transactional(readOnly = true)
    fun allocationsFor(tenantId: UUID, workOrderId: UUID): List<InventoryFulfillmentAllocation> =
        recovered.findByWorkOrder(tenantId, workOrderId).filter { it.active }.map { row ->
            InventoryFulfillmentAllocation(
                targetId = row.id,
                itemId = row.itemId,
                skuId = row.itemId,
                // Van stock teknisi, BUKAN gudang (D6c): barangnya ada di mobilnya, bukan di rak.
                locationId = row.technicianLocationId,
                customerId = row.customerId,
                quantity = 1,
                // Selalu berserial: hanya unit berserial yang bisa ditarik (D2).
                serialized = true,
                actorId = row.technicianId,
                itemCategory = row.itemCategory,
                assetId = row.assetId,
                serialNumber = row.serialNumber,
                returned = true,
            )
        }

    // -------------------------------------------------------------- Internal

    /**
     * (D6d) Tenant untuk SELURUH jalur di kelas ini diambil dari [TenantContext], bukan dari
     * parameter.
     *
     * Query native maupun JPA sama-sama berjalan di bawah RLS Postgres: koneksi dengan GUC
     * `app.tenant_id` yang salah (atau kosong) memulangkan NOL BARIS TANPA ERROR. Kalau tenant
     * diambil dari parameter, penarikan yang ditujukan ke tenant lain tidak meledak — ia hanya
     * "tidak menemukan apa-apa", dan gejalanya identik dengan nomor seri yang memang salah ketik.
     *
     * Parameternya tetap dibandingkan supaya ketidakcocokan itu berteriak alih-alih diam.
     */
    private fun requireTenant(claimed: UUID): UUID {
        val active = TenantContext.tenantId()
        if (active != claimed) {
            throw ValidationException("Penarikan aset lintas tenant ditolak")
        }
        return active
    }

    private fun itemOf(tenantId: UUID, itemId: UUID): InventoryItem =
        items.findById(itemId)?.takeIf { it.tenantId == tenantId }
            ?: throw NotFoundException("Item gudang tidak ditemukan")

    /**
     * Katalog barang untuk SELURUH baris penarikan sekaligus, sebanding dengan [peopleNames].
     *
     * Dulu [recoveredAssets] memanggil [itemOf] per baris — N+1 terhadap master barang, dan satu
     * WO DISMANTLE di gedung bertingkat menarik puluhan ONT sekaligus. Satu unit berserial per
     * baris pula, jadi barisnya tidak pernah diringkas seperti barang curah.
     *
     * Item yang hilang TETAP melempar, persis seperti [itemOf]: baris penarikan menunjuk item
     * lewat FK, jadi id yang tak teresolusi berarti master barangnya rusak — dan memulangkan
     * baris tanpa nama barang hanya memindahkan kerusakan itu ke layar sebagai kolom kosong.
     */
    private fun itemsOf(tenantId: UUID, rows: Collection<WorkOrderRecoveredAsset>): Map<UUID, InventoryItem> {
        val wanted = rows.mapTo(mutableSetOf()) { it.itemId }
        val found = items.findAllByIds(wanted).filter { it.tenantId == tenantId }.associateBy { it.id }
        if (found.size != wanted.size) throw NotFoundException("Item gudang tidak ditemukan")
        return found
    }

    /**
     * SATU panggilan direktori pengguna untuk SELURUH baris penarikan yang akan dinamai.
     *
     * Tiap baris menyebut sampai tiga orang (teknisi, peng-scan, pembatal) dan satu WO DISMANTLE
     * bisa menarik banyak unit; menanyakannya per baris langsung jadi N+1.
     */
    private fun peopleNames(rows: Collection<WorkOrderRecoveredAsset>) = WorkOrderPeopleNames.resolve(
        iam,
        buildSet {
            rows.forEach { row ->
                add(row.technicianId)
                add(row.recoveredBy)
                row.cancelledBy?.let { add(it) }
            }
        },
    )

    /**
     * SATU pembacaan lokasi untuk SELURUH baris yang akan diberi kode van.
     *
     * Bentuknya sama dengan [peopleNames]: id-nya dikumpulkan ke satu himpunan dulu, baru satu
     * panggilan. `findById` per baris adalah N+1 yang persis tumbuh seiring banyaknya unit yang
     * ditarik dalam satu WO DISMANTLE — dan WO borongan (satu pelanggan pindah alamat, lima ONT
     * dicabut) adalah kasus yang justru paling sering dibaca.
     *
     * Dipakai `findAll` sekali lalu disaring, bukan query "where id in (...)": itu pola yang
     * sudah dipakai `InventoryStockQueryService.names` untuk keperluan yang sama, dan daftar
     * lokasi satu tenant memang berukuran puluhan. Himpunan kosong TIDAK memanggil apa pun.
     */
    private fun locationCodes(tenantId: UUID, rows: Collection<WorkOrderRecoveredAsset>): Map<UUID, String> {
        val ids = rows.mapTo(mutableSetOf()) { it.technicianLocationId }
        if (ids.isEmpty()) return emptyMap()
        return locations.findAll(tenantId).filter { it.id in ids }.associate { it.id to it.code }
    }

    private fun WorkOrderRecoveredAsset.toView(
        item: InventoryItem,
        names: WorkOrderPeopleNames,
        vans: Map<UUID, String>,
    ) = WorkOrderRecoveredAssetView(
        id = id,
        workOrderId = workOrderId,
        assetId = assetId,
        serialNumber = serialNumber,
        macAddress = macAddress,
        itemId = itemId,
        itemCode = item.code,
        itemName = item.name,
        itemCategory = itemCategory,
        customerId = customerId,
        technicianId = technicianId,
        technicianName = names.person(technicianId),
        technicianLocationId = technicianLocationId,
        // Fallback UUID, BUKAN string kosong: sel kosong terbaca "unit ini tidak ada vannya",
        // padahal yang terjadi adalah lokasinya sudah terhapus. UUID yang jelek itu petunjuk.
        technicianLocationCode = vans[technicianLocationId] ?: technicianLocationId.toString(),
        condition = condition.name,
        note = note,
        recoveredAt = recoveredAt,
        recoveredBy = recoveredBy,
        recoveredByName = names.person(recoveredBy),
        cancelledAt = cancelledAt,
        cancelledBy = cancelledBy,
        // `null` HANYA kalau barisnya memang belum dibatalkan.
        cancelledByName = names.personOrNull(cancelledBy),
        cancelReason = cancelReason,
    )
}

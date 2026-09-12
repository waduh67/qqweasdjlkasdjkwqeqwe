package com.duluin.ftth.inventory.application.service

import com.duluin.ftth.common.domain.Page
import com.duluin.ftth.common.domain.PageRequest
import com.duluin.ftth.iam.IamApi
import com.duluin.ftth.inventory.application.port.outbound.InventoryItemRepository
import com.duluin.ftth.inventory.application.port.outbound.InventoryLocationRepository
import com.duluin.ftth.inventory.application.port.outbound.MovementFilter
import com.duluin.ftth.inventory.application.port.outbound.SerializedAssetRepository
import com.duluin.ftth.inventory.domain.model.*
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Pertanyaan yang selalu diajukan orang gudang tapi belum punya jawaban di sistem ini:
 * "mutasi apa saja yang terjadi", "berapa sisa barang X di gudang Y", "apa saja yang dibawa
 * teknisi Z", dan "di mana angkanya tidak cocok".
 *
 * Semua id barang, lokasi, dan ORANG DITERJEMAHKAN jadi kode/nama di sini. Mengembalikan UUID
 * telanjang membuat layar gudang mustahil dibaca dan memaksa web memanggil dua endpoint
 * tambahan hanya untuk menampilkan satu baris.
 *
 * Kenapa nama orang ikut dibawa server, bukan digabungkan klien seperti sebelumnya: layar
 * gudang yang menggabungkan sendiri terhadap `/item-master` dan `/api/users` memaksa SETIAP
 * pembacanya punya `inventory.item.view` dan `iam.user.view`. Petugas gudang biasa TIDAK punya
 * keduanya — ia kena 403 pada panggilan penggabungan itu lalu membaca kode barang dan UUID
 * orang telanjang di layar. Resolusi di sini berjalan IN-PROCESS lewat [IamApi], jadi tidak ada
 * pemeriksaan izin direktori pengguna yang perlu dilewati sama sekali.
 */
@Service
class InventoryStockQueryService(
    private val ledger: InventoryMovementLedgerService,
    private val items: InventoryItemRepository,
    private val locations: InventoryLocationRepository,
    private val assets: SerializedAssetRepository,
    private val reconciliation: InventoryReconciliationService,
    private val iam: IamApi,
) {

    /**
     * Urutannya SENGAJA: ambil halamannya DULU, kumpulkan id orangnya, baru bangun [NameBook].
     *
     * Kamus nama tidak bisa lagi dibangun di awal tanpa argumen seperti dulu, karena himpunan id
     * orang yang perlu diresolusi hanya diketahui setelah barisnya diambil — dan meresolusinya
     * per baris akan melahirkan N+1: satu halaman 20 mutasi berisi 20 aktor plus sampai 40 leg
     * dengan pemegang custody masing-masing, jadi puluhan query untuk satu layar.
     */
    @Transactional(readOnly = true)
    fun ledgerPage(tenantId: UUID, filter: MovementFilter, page: PageRequest): Page<MovementEntryView> {
        val rows = ledger.movementPage(tenantId, filter, page)
        val names = names(
            tenantId,
            buildSet {
                rows.content.forEach { movement ->
                    add(movement.actorId)
                    movement.legs.forEach { add(it.custodyOwnerId) }
                }
            },
        )
        return rows.map { it.toView(names) }
    }

    @Transactional(readOnly = true)
    fun balances(tenantId: UUID, itemId: UUID? = null, locationId: UUID? = null): List<StockBalanceView> {
        // Saring DULU baru kumpulkan id pemegang custody-nya: halaman saldo yang disaring per item
        // tidak boleh menyeret seluruh direktori pengguna hanya karena baris lain ada di tenant ini.
        val rows = ledger.balances(tenantId)
            .filter { (itemId == null || it.itemId == itemId) && (locationId == null || it.locationId == locationId) }
        val names = names(tenantId, rows.mapTo(mutableSetOf()) { it.custodyOwnerId })
        return rows
            .map { it.toView(names) }
            .sortedWith(compareBy({ it.itemCode }, { it.locationCode }, { it.status }))
    }

    /**
     * Van stock: barang yang sedang dipegang teknisi, bukan yang ada di gudang.
     *
     * Disaring lewat `custodyOwnerKind = TECHNICIAN`, BUKAN lewat jenis lokasinya: teknisi
     * yang sedang berada di gudang tetap memegang barangnya sendiri, dan menyaring pakai
     * lokasi akan membuat isi mobilnya menghilang dari laporan setiap kali ia mampir.
     */
    @Transactional(readOnly = true)
    fun vanStock(tenantId: UUID, technicianId: UUID? = null): List<VanStockView> {
        // Saldonya DULU, karena himpunan teknisi yang perlu dinamai baru terbentuk setelah
        // penyaringan: memanggil direktori pengguna per mobil akan jadi satu query per teknisi.
        val quantities = ledger.balances(tenantId)
            .filter { it.custodyOwnerKind == OwnerKind.TECHNICIAN && (technicianId == null || it.custodyOwnerId == technicianId) }
        val names = names(tenantId, quantities.mapTo(mutableSetOf()) { it.custodyOwnerId })
        val serials = assets.findAll(tenantId)
            .filter { it.custody.ownerKind == OwnerKind.TECHNICIAN && (technicianId == null || it.custody.ownerId == technicianId) }
            .groupBy { it.custody.ownerId to it.skuId }
        return quantities
            .groupBy { it.custodyOwnerId }
            .map { (owner, rows) ->
                VanStockView(
                    owner, names.person(owner),
                    rows.map { row ->
                        VanStockLineView(
                            row.itemId, names.itemCode(row.itemId), names.itemName(row.itemId),
                            row.locationId, names.locationCode(row.locationId), names.locationKind(row.locationId),
                            row.status, row.quantity,
                            serials[owner to row.itemId].orEmpty().filter { it.status == row.status }.map { it.serialNumber }.sorted(),
                        )
                    }.sortedWith(compareBy({ it.itemCode }, { it.status })),
                )
            }
            .sortedBy { it.technicianId.toString() }
    }

    /**
     * Stock opname yang masih menggantung, SENDIRIAN — tanpa anomali saldo.
     *
     * Ada dua pembacanya dan keduanya butuh bentuk yang berbeda: layar "Laporan selisih" mau
     * keduanya sekaligus lewat [varianceReport], sedangkan tab stock opname hanya mau daftar
     * yang menunggu keputusan. Sebelum ini yang kedua dilayani dengan mengembalikan agregat
     * `CycleCount` mentah, yang memaksa web menggabungkan id barang/lokasi/orangnya sendiri —
     * persis masalah yang read model ini ada untuk menghapusnya — sekaligus menyiarkan
     * `skuId`, `operationHash`, dan `tenantId` ke browser tanpa satu pun layar memakainya.
     */
    @Transactional(readOnly = true)
    fun openCounts(tenantId: UUID): List<OpenCountView> {
        val rows = reconciliation.open(tenantId)
        return rows.toViews(names(tenantId, rows.mapTo(mutableSetOf()) { it.custodianId }))
    }

    /**
     * Laporan selisih: stock opname yang masih menggantung plus saldo yang tidak masuk akal.
     *
     * Keduanya digabung SENGAJA — selisih yang ditemukan manusia (opname) dan selisih yang
     * ditemukan sistem (saldo negatif atau tak cocok dengan daftar aset) harus muncul di satu
     * layar. Kalau dipisah, tidak ada seorang pun yang membuka keduanya setiap hari.
     */
    @Transactional(readOnly = true)
    fun varianceReport(tenantId: UUID): VarianceReportView {
        // Opname yang menggantung dibaca DULU supaya id petugas hitungnya bisa dikumpulkan jadi
        // satu himpunan; anomali saldo tidak menyebut orang sama sekali, jadi tidak menambah id.
        val openCounts = reconciliation.open(tenantId)
        // Kamusnya dibangun SEKALI di sini lalu dipinjamkan ke `toViews`, bukan dengan memanggil
        // `openCounts()` di atas: memanggilnya akan membangun NameBook kedua, artinya master
        // barang dan lokasi dibaca dua kali untuk satu layar yang sama.
        val names = names(tenantId, openCounts.mapTo(mutableSetOf()) { it.custodianId })
        val open = openCounts.toViews(names)
        // Saldo per unit fisik harus cocok dengan jumlah baris aset serial yang berstatus sama:
        // kalau tidak, ada unit yang tercatat di salah satu sisi saja — persis bentuk kebocoran
        // yang paling sering luput karena kedua angkanya masing-masing terlihat wajar.
        val assetCounts = assets.findAll(tenantId)
            .groupingBy { Triple(it.skuId, it.locationId, it.status) }.eachCount()
        val mismatches = ledger.balances(tenantId).mapNotNull { balance ->
            val counted = assetCounts[Triple(balance.itemId, balance.locationId, balance.status)] ?: 0
            val serializedItem = counted > 0
            when {
                balance.quantity < 0 -> BalanceAnomalyView(
                    balance.itemId, names.itemCode(balance.itemId), names.itemName(balance.itemId),
                    balance.locationId, names.locationCode(balance.locationId), names.locationKind(balance.locationId),
                    balance.status, balance.quantity, counted, "saldo negatif",
                )
                serializedItem && counted != balance.quantity -> BalanceAnomalyView(
                    balance.itemId, names.itemCode(balance.itemId), names.itemName(balance.itemId),
                    balance.locationId, names.locationCode(balance.locationId), names.locationKind(balance.locationId),
                    balance.status, balance.quantity, counted, "saldo tidak cocok dengan jumlah aset serial",
                )
                else -> null
            }
        }
        return VarianceReportView(open, mismatches)
    }

    /**
     * Kamus kode/nama barang, lokasi, dan orang — masing-masing dibaca SEKALI per permintaan.
     *
     * Kalau setiap baris mencari namanya sendiri, satu halaman riwayat 20 baris dengan 40 leg
     * menghasilkan puluhan query tambahan — N+1 yang tumbuh persis seiring ramainya gudang.
     * Karena itu [personIds] diterima sebagai SATU himpunan yang sudah lengkap: pemanggilnya
     * WAJIB mengambil barisnya lebih dulu dan mengumpulkan seluruh id orangnya, bukan memanggil
     * `findUser` per baris.
     *
     * Seluruh pembacaan berjalan di dalam `@Transactional(readOnly = true)` dengan tenant aktif
     * dari `TenantContext`; [IamApi] ter-scope tenant yang sama lewat RLS, jadi id milik tenant
     * lain yang entah bagaimana masuk ke himpunan ini memang tidak akan menemukan barisnya dan
     * jatuh ke fallback UUID — bukan membocorkan nama orang tenant sebelah.
     */
    private fun names(tenantId: UUID, personIds: Set<UUID>): NameBook {
        val itemRows = items.findAll(tenantId)
        val locationRows = locations.findAll(tenantId)
        val locationsById = locationRows.associateBy { it.id }
        // Id yang ternyata id LOKASI tidak perlu ditanyakan ke direktori pengguna: `custodyOwnerId`
        // milik gudang memang menunjuk lokasi, dan menanyakannya hanya menambah beban tanpa
        // pernah menghasilkan baris. Sisanya ditanya SEKALI, dan hanya kalau memang ada.
        val askIam = personIds - locationsById.keys
        val userNames = if (askIam.isEmpty()) emptyMap() else iam.usersByIds(askIam).associate { it.id to it.name }
        return NameBook(
            itemCodes = itemRows.associate { it.id to it.code },
            // Item NONAKTIF ikut dinamai — `findAll` memang tidak menyaringnya. Ledger lama justru
            // penuh menunjuk barang yang sudah dipensiunkan, dan baris itulah yang paling butuh
            // dibaca manusia; menyaring yang nonaktif akan membuatnya jadi "(tidak dikenal)".
            itemNames = itemRows.associate { it.id to it.name },
            locationCodes = locationRows.associate { it.id to it.code },
            locationKinds = locationRows.associate { it.id to it.kind },
            userNames = userNames,
        )
    }

    private class NameBook(
        private val itemCodes: Map<UUID, String>,
        private val itemNames: Map<UUID, String>,
        private val locationCodes: Map<UUID, String>,
        private val locationKinds: Map<UUID, LocationKind>,
        private val userNames: Map<UUID, String>,
    ) {
        fun itemCode(id: UUID) = itemCodes[id] ?: "(tidak dikenal)"
        fun itemName(id: UUID) = itemNames[id] ?: "(tidak dikenal)"
        fun locationCode(id: UUID) = locationCodes[id] ?: "(tidak dikenal)"

        /**
         * Nullable SENGAJA, dan satu-satunya field nama di sini yang boleh kosong.
         *
         * `InventoryLocation` tidak punya kolom nama, hanya `code` + `kind` — yang dibawa adalah
         * `kind`-nya karena persis itu yang digabungkan klien sekarang (label jenis sebagai baris
         * kedua di bawah kode lokasi). Lokasi yang sudah terhapus TIDAK BOLEH dipalsukan jadi
         * jenis tertentu: menebak `TRANSIT` atau `WAREHOUSE` akan membuat baris saldo yatim
         * terlihat seperti stok yang jelas tempatnya, dan tidak ada satu pun cara membedakannya
         * lagi dari baris yang lokasinya memang masih ada. `null` berkata "tidak tahu", dan itu
         * satu-satunya jawaban yang jujur.
         */
        fun locationKind(id: UUID) = locationKinds[id]

        /**
         * Nama seorang pengguna; fallback ke UUID-nya, BUKAN string kosong.
         *
         * Sel kosong di layar terbaca "tidak ada orangnya" — padahal yang terjadi adalah id yang
         * tidak teresolusi (pengguna terhapus, atau id milik tenant lain). UUID yang tampil jelek
         * itu justru petunjuk yang bisa ditindaklanjuti.
         */
        fun person(id: UUID) = userNames[id] ?: id.toString()

        /**
         * Pemegang custody: LOKASI dulu, baru ORANG, baru UUID.
         *
         * `custodyOwnerId` artinya bergantung pada `custodyOwnerKind` — untuk TECHNICIAN ia id
         * pengguna, untuk WAREHOUSE/VEHICLE lazimnya id lokasi, dan untuk CUSTOMER ia milik modul
         * pelanggan. Resolusinya SENGAJA tidak bercabang pada `kind` melainkan mencoba kamus yang
         * sudah ada: id lokasi dan id pengguna hidup di ruang UUID yang sama dan tidak pernah
         * bertabrakan, jadi "kenal sebagai lokasi" adalah jawaban yang pasti benar. Lokasi didahulukan
         * karena baris gudang jauh lebih banyak dan kamusnya sudah di tangan tanpa query tambahan.
         *
         * CUSTOMER memang berakhir sebagai UUID, dan itu keputusan: menanyakan id itu ke modul
         * pelanggan melahirkan siklus modul (inventory -> customer, sementara alur fulfillment
         * sudah berjalan ke arah sebaliknya). Lebih baik satu kolom yang jujur tak terbaca
         * daripada dua modul yang saling mengunci.
         *
         * Klien sekarang keliru SELALU memakai direktori pengguna untuk kolom ini, jadi setiap
         * baris milik gudang tampil sebagai UUID telanjang. Inilah yang menghapusnya.
         */
        fun custodyOwner(id: UUID) = locationCodes[id] ?: userNames[id] ?: id.toString()
    }

    private fun List<CycleCount>.toViews(names: NameBook) = map {
        OpenCountView(
            it.countId, it.itemId, names.itemCode(it.itemId), names.itemName(it.itemId),
            it.locationId, names.locationCode(it.locationId), names.locationKind(it.locationId),
            it.priorQuantity, it.observedQuantity, it.observedQuantity - it.priorQuantity,
            it.custodianId, names.person(it.custodianId), it.reason, it.discrepancy, it.createdAt,
        )
    }

    private fun InventoryMovement.toView(names: NameBook) = MovementEntryView(
        movementId, kind, state, reason, actorId, names.person(actorId), serverReceivedAt, operationKey, compensatesMovementId,
        legs.map { leg ->
            MovementLegView(
                leg.direction, leg.itemId, names.itemCode(leg.itemId), names.itemName(leg.itemId),
                leg.locationId, names.locationCode(leg.locationId), names.locationKind(leg.locationId),
                leg.quantity, leg.status, leg.custodyOwnerId, names.custodyOwner(leg.custodyOwnerId),
                leg.custodyOwnerKind, leg.assetId, leg.serialNumber,
            )
        },
    )

    private fun InventoryBalance.toView(names: NameBook) = StockBalanceView(
        itemId, names.itemCode(itemId), names.itemName(itemId),
        locationId, names.locationCode(locationId), names.locationKind(locationId),
        custodyOwnerId, names.custodyOwner(custodyOwnerId), custodyOwnerKind, status, quantity,
    )
}

data class MovementEntryView(
    val movementId: UUID,
    val kind: MovementKind,
    val state: MovementState,
    val reason: String,
    val actorId: UUID,
    val actorName: String,
    val occurredAt: java.time.Instant,
    val operationKey: String,
    val compensatesMovementId: UUID?,
    val legs: List<MovementLegView>,
)

data class MovementLegView(
    val direction: LegDirection,
    val itemId: UUID,
    val itemCode: String,
    val itemName: String,
    val locationId: UUID,
    val locationCode: String,
    /** `null` HANYA bila lokasinya tak dikenal lagi — lihat `NameBook.locationKind`. */
    val locationKind: LocationKind?,
    val quantity: Int,
    val status: InventoryStatus,
    val custodyOwnerId: UUID,
    val custodyOwnerName: String,
    val custodyOwnerKind: OwnerKind,
    val assetId: UUID?,
    val serialNumber: String?,
)

data class StockBalanceView(
    val itemId: UUID,
    val itemCode: String,
    val itemName: String,
    val locationId: UUID,
    val locationCode: String,
    /** `null` HANYA bila lokasinya tak dikenal lagi — lihat `NameBook.locationKind`. */
    val locationKind: LocationKind?,
    val custodyOwnerId: UUID,
    val custodyOwnerName: String,
    val custodyOwnerKind: OwnerKind,
    val status: InventoryStatus,
    val quantity: Int,
)

data class VanStockView(val technicianId: UUID, val technicianName: String, val lines: List<VanStockLineView>)

data class VanStockLineView(
    val itemId: UUID,
    val itemCode: String,
    val itemName: String,
    val locationId: UUID,
    val locationCode: String,
    /** `null` HANYA bila lokasinya tak dikenal lagi — lihat `NameBook.locationKind`. */
    val locationKind: LocationKind?,
    val status: InventoryStatus,
    val quantity: Int,
    val serialNumbers: List<String>,
)

data class OpenCountView(
    val countId: UUID,
    val itemId: UUID,
    val itemCode: String,
    val itemName: String,
    val locationId: UUID,
    val locationCode: String,
    /** `null` HANYA bila lokasinya tak dikenal lagi — lihat `NameBook.locationKind`. */
    val locationKind: LocationKind?,
    val priorQuantity: Int,
    val observedQuantity: Int,
    val delta: Int,
    val custodianId: UUID,
    val custodianName: String,
    /**
     * Alasan opname dicatat, apa adanya dari petugasnya.
     *
     * Ikut dibawa karena penyetuju selisih menilai DUA hal: angkanya dan keterangannya. Tanpa
     * ini layar persetujuan hanya menyodorkan "-3 pcs" tanpa "rusak kena air saat banjir", dan
     * keputusan empat-mata berubah jadi menekan tombol setuju atas angka tanpa konteks.
     */
    val reason: String,
    val state: DiscrepancyState,
    val countedAt: java.time.Instant,
)

data class BalanceAnomalyView(
    val itemId: UUID,
    val itemCode: String,
    val itemName: String,
    val locationId: UUID,
    val locationCode: String,
    /** `null` HANYA bila lokasinya tak dikenal lagi — lihat `NameBook.locationKind`. */
    val locationKind: LocationKind?,
    val status: InventoryStatus,
    val projectedQuantity: Int,
    val serializedAssetCount: Int,
    val issue: String,
)

data class VarianceReportView(val openCounts: List<OpenCountView>, val anomalies: List<BalanceAnomalyView>)

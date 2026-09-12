package com.duluin.ftth.inventory.application.service

import com.duluin.ftth.common.domain.error.ConflictException
import com.duluin.ftth.common.domain.error.NotFoundException
import com.duluin.ftth.common.domain.error.ValidationException
import com.duluin.ftth.inventory.application.port.outbound.InventoryLocationRepository
import com.duluin.ftth.inventory.application.port.outbound.SerializedAssetRepository
import com.duluin.ftth.inventory.domain.model.*
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Operasi gudang sehari-hari: terima barang, transfer, keluarkan, retur, dan penyesuaian.
 *
 * Semuanya bermuara ke satu tempat — [InventoryMovementLedgerService]. Tidak ada jalur lain
 * yang boleh menggerakkan saldo: begitu ada dua jalan masuk, salah satunya cepat atau lambat
 * akan lupa memvalidasi stok atau lupa menulis leg, dan selisihnya baru ketahuan saat stok
 * fisik diadu dengan sistem berbulan-bulan kemudian.
 *
 * Yang berisiko (RESTOCK dan seluruh penyesuaian) lahir PENDING_APPROVAL dan LANGSUNG
 * membuat permintaan persetujuan yang terikat ke mutasinya — tanpa pengikatan itu, mutasi
 * menggantung tanpa ada yang pernah diminta menyetujuinya.
 */
@Service
class InventoryOperationsService(
    private val ledger: InventoryMovementLedgerService,
    private val approvals: InventoryApprovalService,
    private val items: InventoryItemService,
    private val locations: InventoryLocationRepository,
    private val assets: SerializedAssetRepository,
) {

    /**
     * Penerimaan barang (GRN) untuk barang curah.
     *
     * Barang BERSERIAL SENGAJA tidak lewat sini: unit bernomor seri masuk lewat
     * [InventoryAssetRegistrationService.registerBulk], yang sekaligus membuat baris asetnya
     * dan menulis mutasi RECEIVE yang sama. Kalau keduanya boleh menerima barang berserial,
     * satu kiriman bisa tercatat dua kali — saldo bertambah dua kali lipat sementara daftar
     * asetnya hanya berisi satu unit per nomor seri.
     */
    @Transactional
    fun receive(command: GoodsReceiptCommand): InventoryMovement {
        val destination = requireLocation(command.tenantId, command.locationId)
        val legs = command.lines.flatMap { line ->
            val item = requireBulkItem(command.tenantId, line, "penerimaan barang")
            listOf(
                MovementLeg(
                    LegDirection.IN, item.id, item.id, destination.id, line.quantity, false,
                    command.custodianId, OwnerKind.WAREHOUSE, InventoryStatus.AVAILABLE,
                ),
            )
        }
        return applyLedger(command.toMovement(MovementKind.RECEIVE, legs))
    }

    /**
     * Permintaan restock: barang dijanjikan masuk, tapi saldonya BELUM bergerak.
     *
     * Mutasinya lahir PENDING_APPROVAL dan baru berlaku ketika rantai persetujuan selesai
     * ([InventoryApprovalService] yang mengeksekusinya). Kalau saldo langsung ditambah di
     * sini, penolakan approval meninggalkan stok yang sudah terlanjur bertambah dan gudang
     * mengaku punya barang yang tak pernah datang.
     */
    @Transactional
    fun requestRestock(command: GoodsReceiptCommand): InventoryOperationResult {
        val destination = requireLocation(command.tenantId, command.locationId)
        val legs = command.lines.map { line ->
            val item = requireBulkItem(command.tenantId, line, "permintaan restock")
            MovementLeg(
                LegDirection.IN, item.id, item.id, destination.id, line.quantity, false,
                command.custodianId, OwnerKind.WAREHOUSE, InventoryStatus.AVAILABLE,
            )
        }
        val movement = applyLedger(command.toMovement(MovementKind.RESTOCK, legs))
        return withApproval(movement, InventoryApprovalType.RESTOCK, command.actorId, command.custodianId, command.emergencyReason)
    }

    /** Pindah antar gudang/bin. Custody tetap di gudang, hanya lokasinya yang berubah. */
    @Transactional
    fun transfer(command: TransferCommand): InventoryMovement {
        val source = requireLocation(command.tenantId, command.fromLocationId)
        val destination = requireLocation(command.tenantId, command.toLocationId)
        if (source.id == destination.id) throw ValidationException("Lokasi asal dan tujuan tidak boleh sama")

        val legs = command.lines.flatMap { line ->
            val item = items.requireActive(line.itemId, command.tenantId)
            val moved = resolveAssets(command.tenantId, item, line, source.id, InventoryStatus.AVAILABLE)
            moved.forEach { asset ->
                assets.save(asset.relocate(destination, CustodyClaim(command.toCustodianId, OwnerKind.WAREHOUSE, destination.id)))
            }
            legPair(
                item, line, moved,
                out = LegEnd(source.id, command.fromCustodianId, OwnerKind.WAREHOUSE, InventoryStatus.AVAILABLE),
                into = LegEnd(destination.id, command.toCustodianId, OwnerKind.WAREHOUSE, InventoryStatus.AVAILABLE),
            )
        }
        return applyLedger(command.toMovement(MovementKind.TRANSFER, legs))
    }

    /** Keluarkan barang ke teknisi (van stock). */
    @Transactional
    fun issue(command: IssueCommand): InventoryMovement {
        val source = requireLocation(command.tenantId, command.fromLocationId)
        val holder = requireLocation(command.tenantId, command.technicianLocationId)
        val legs = command.lines.flatMap { line ->
            val item = items.requireActive(line.itemId, command.tenantId)
            val moved = resolveAssets(command.tenantId, item, line, source.id, InventoryStatus.AVAILABLE)
            moved.forEach { asset ->
                assets.save(asset.transition(InventoryStatus.ISSUED, holder, CustodyClaim(command.technicianId, OwnerKind.TECHNICIAN, holder.id)))
            }
            legPair(
                item, line, moved,
                out = LegEnd(source.id, command.custodianId, OwnerKind.WAREHOUSE, InventoryStatus.AVAILABLE),
                into = LegEnd(holder.id, command.technicianId, OwnerKind.TECHNICIAN, InventoryStatus.ISSUED),
            )
        }
        return applyLedger(command.toMovement(MovementKind.ISSUE, legs))
    }

    /**
     * Retur dari teknisi ke gudang.
     *
     * Barang rusak masuk KARANTINA, bukan langsung tersedia: kalau semuanya dikembalikan
     * sebagai AVAILABLE, unit cacat akan dikeluarkan lagi ke teknisi berikutnya dan
     * perjalanan bolak-baliknya tidak pernah terlihat di laporan mana pun.
     */
    @Transactional
    fun returnToWarehouse(command: ReturnCommand): InventoryMovement {
        val holder = requireLocation(command.tenantId, command.fromLocationId)
        val destination = requireLocation(command.tenantId, command.toLocationId)
        val arrivalStatus = if (command.quarantine) InventoryStatus.QUARANTINE else InventoryStatus.AVAILABLE
        val legs = command.lines.flatMap { line ->
            val item = items.requireActive(line.itemId, command.tenantId)
            val moved = resolveAssets(command.tenantId, item, line, holder.id, InventoryStatus.ISSUED)
            moved.forEach { asset ->
                // Dua langkah karena tabel transisi memang mengharuskannya: ISSUED -> RETURNED
                // mencatat bahwa barang kembali, RETURNED -> AVAILABLE/QUARANTINE mencatat
                // hasil pemeriksaannya. Melompati yang pertama menghapus jejak bahwa unit ini
                // pernah dipegang teknisi.
                val returned = asset.transition(InventoryStatus.RETURNED, destination, CustodyClaim(command.custodianId, OwnerKind.WAREHOUSE, destination.id))
                assets.save(returned.transition(arrivalStatus, destination, CustodyClaim(command.custodianId, OwnerKind.WAREHOUSE, destination.id)))
            }
            legPair(
                item, line, moved,
                out = LegEnd(holder.id, command.technicianId, OwnerKind.TECHNICIAN, InventoryStatus.ISSUED),
                into = LegEnd(destination.id, command.custodianId, OwnerKind.WAREHOUSE, arrivalStatus),
            )
        }
        return applyLedger(command.toMovement(MovementKind.RETURN, legs))
    }

    /**
     * Penyesuaian stok: koreksi, susut, rusak, hapus buku — untuk barang curah MAUPUN unit
     * berserial.
     *
     * Semuanya lahir PENDING_APPROVAL — inilah jenis mutasi yang paling mudah dipakai
     * menutupi kebocoran aset, jadi tidak satu pun boleh berlaku tanpa mata kedua.
     *
     * Unit berserial ikut lewat sini, bukan lewat endpoint sendiri, karena keputusannya persis
     * sama (siapa yang boleh menyatakan barang hilang) dan matriks persetujuannya pun sama.
     * Yang berbeda hanya bentuk leg-nya: lihat [writeOffLegs].
     */
    @Transactional
    fun adjust(command: AdjustmentCommand): InventoryOperationResult {
        val location = requireLocation(command.tenantId, command.locationId)
        val kind = command.kind.movementKind
        val legs = command.lines.flatMap { line ->
            val item = items.requireActive(line.itemId, command.tenantId)
            if (item.serialized) return@flatMap writeOffLegs(command, item, line, location)
            requireBulkItem(command.tenantId, line, "penyesuaian stok")
            // Hanya ADJUSTMENT yang boleh menambah; susut/rusak/hapus buku SELALU mengurangi.
            // Tanpa aturan ini, "hapus buku" bisa dipakai menambah stok tanpa dokumen masuk
            // apa pun, dan justru itu bentuk kebocoran yang paling sulit dilihat di laporan.
            val direction = if (command.increase && command.kind == AdjustmentKind.CORRECTION) LegDirection.IN else LegDirection.OUT
            listOf(
                MovementLeg(
                    direction, item.id, item.id, location.id, line.quantity, false,
                    command.custodianId, OwnerKind.WAREHOUSE, command.kind.status,
                ),
            )
        }
        val movement = applyLedger(command.toMovement(kind, legs))
        return withApproval(movement, command.kind.approvalType, command.actorId, command.custodianId, command.emergencyReason)
    }

    /**
     * Leg penghapusbukuan satu unit berserial: OUT dari dimensi tempat unitnya BENAR-BENAR
     * berdiri, IN ke dimensi terminal di lokasi yang sama.
     *
     * Dua keputusan yang perlu dijelaskan:
     *
     * 1. Sisi OUT dibaca dari BARIS ASETNYA (lokasi, pemegang, status), bukan dari isi
     *    permintaan. Petugas boleh saja mengirim custodianId yang berbeda dengan pemegang yang
     *    tercatat; kalau itu dipakai, saldo dipotong dari dimensi yang tidak pernah berisi unit
     *    ini — dimensi aslinya tetap penuh dan yang dipotong jadi minus (atau ditolak "stok
     *    tidak cukup" tanpa alasan yang masuk akal bagi petugas).
     *
     * 2. Unitnya TIDAK dilenyapkan dari saldo, melainkan dipindahkan ke dimensi LOST/DISPOSED.
     *    Barang yang dihapusbukukan tetap harus bisa dihitung — "berapa unit yang hilang di
     *    gudang ini tahun lalu" adalah pertanyaan audit pertama, dan ia tidak bisa dijawab oleh
     *    saldo yang hanya berkurang tanpa sisa. Sekaligus inilah yang membuat proyeksi saldo
     *    dan daftar aset sepakat unit demi unit: dimensi tujuan leg IN persis sama dengan
     *    status + custody yang akan dipakai baris asetnya.
     */
    private fun writeOffLegs(
        command: AdjustmentCommand,
        item: InventoryItem,
        line: StockLine,
        location: InventoryLocation,
    ): List<MovementLeg> {
        // CORRECTION tidak punya status terminal, dan memang tidak boleh punya: menambah atau
        // mengurangi JUMLAH barang bernomor seri tidak berarti apa-apa — yang ada hanya unit
        // tertentu yang hilang, rusak, atau dihapusbukukan.
        val terminal = command.kind.movementKind.settlesSerialTo
            ?: throw ValidationException(
                "Item ${item.code} berserial: pakai susut, rusak, atau hapus buku dengan menyebut nomor seri, bukan koreksi jumlah",
            )
        val units = resolveWriteOffAssets(command.tenantId, item, line, location.id, terminal)
        return units.flatMap { asset ->
            listOf(
                MovementLeg(
                    LegDirection.OUT, item.id, item.id, asset.locationId, 1, true,
                    asset.custody.ownerId, asset.custody.ownerKind, asset.status, asset.id, asset.serialNumber,
                ),
                MovementLeg(
                    LegDirection.IN, item.id, item.id, asset.locationId, 1, true,
                    asset.custody.ownerId, terminal.requiredOwnerKind() ?: asset.custody.ownerKind, terminal,
                    asset.id, asset.serialNumber,
                ),
            )
        }
    }

    /**
     * Ambil unit yang akan dihapusbukukan dan tolak di muka yang tidak mungkin.
     *
     * Bedanya dengan [resolveAssets]: di sini status asal TIDAK dipatok satu nilai — barang bisa
     * hilang dari rak, dari tas teknisi, maupun dari karantina. Yang menentukan sah atau tidak
     * adalah tabel transisi aset itu sendiri ([SerializedAsset.canSettleTo]), tabel yang SAMA
     * yang nanti dipakai ledger saat mutasinya benar-benar berlaku. Kalau pemeriksaan ini
     * memakai daftar status sendiri, permintaan bisa lolos hari ini lalu meledak berjam-jam
     * kemudian di tangan approver — yang tidak bisa berbuat apa-apa selain menolaknya.
     */
    private fun resolveWriteOffAssets(
        tenantId: UUID,
        item: InventoryItem,
        line: StockLine,
        locationId: UUID,
        terminal: InventoryStatus,
    ): List<SerializedAsset> {
        if (line.quantity <= 0) throw ValidationException("Jumlah harus lebih dari nol")
        if (line.serialNumbers.size != line.quantity) {
            throw ValidationException("Item ${item.code} berserial: jumlah nomor seri harus sama dengan kuantitas")
        }
        return line.serialNumbers.map { serial ->
            val asset = assets.findBySerial(tenantId, serial.trim())
                ?: throw NotFoundException("Nomor seri ${serial.trim()} tidak terdaftar")
            if (asset.skuId != item.id) throw ValidationException("Nomor seri ${asset.serialNumber} bukan milik item ${item.code}")
            if (asset.locationId != locationId) throw ConflictException("Nomor seri ${asset.serialNumber} tidak berada di lokasi yang disebut")
            if (!asset.canSettleTo(terminal)) {
                throw ConflictException("Nomor seri ${asset.serialNumber} berstatus ${asset.status} dan tidak bisa dinyatakan $terminal")
            }
            asset
        }
    }

    /**
     * Buat permintaan persetujuan yang TERIKAT pada mutasinya.
     *
     * `amount` diisi TOTAL KUANTITAS, bukan nilai rupiah: tidak ada satu pun harga barang di
     * skema gudang saat ini, jadi ambang kebijakan hanya bisa jujur dinyatakan dalam jumlah
     * unit. Begitu harga item ada, ambangnya bisa pindah ke nilai tanpa mengubah bentuk
     * kebijakan — itu sebabnya kolomnya dinamai `minimum_amount`, bukan `minimum_quantity`.
     *
     * KETERBATASAN yang perlu diketahui sebelum menyetel ambang: satu ambang berlaku untuk
     * seluruh item dalam satu jenis persetujuan, padahal satuannya bercampur. Ambang yang pas
     * untuk 200 METER dropcore jadi terlalu longgar untuk 200 PCS ONT — barang mahal justru
     * yang paling mudah lolos di bawah ambang yang disetel untuk barang murah.
     */
    private fun withApproval(
        movement: InventoryMovement,
        type: InventoryApprovalType,
        requesterId: UUID,
        custodianId: UUID,
        emergencyReason: String?,
    ): InventoryOperationResult {
        if (movement.state != MovementState.PENDING_APPROVAL) {
            // Replay: mutasi yang sama sudah pernah diajukan dan sudah lewat approval.
            return InventoryOperationResult(movement, null)
        }
        val approval = approvals.request(
            CreateInventoryApproval(
                movement.tenantId, type, approvalAmount(movement), requesterId, custodianId,
                "${movement.operationKey}:approval", movement.payloadHash, movement.movementId, emergencyReason,
            ),
        )
        // Mutasi dibaca ulang: override darurat membuat permintaan lahir APPROVED dan
        // efeknya SUDAH memberlakukan mutasi di dalam panggilan di atas. Mengembalikan objek
        // lama akan melaporkan PENDING_APPROVAL untuk mutasi yang saldonya sudah bergerak.
        return InventoryOperationResult(ledger.movement(movement.movementId) ?: movement, approval)
    }

    /**
     * Besaran yang diadu dengan ambang kebijakan: sisi TERBESAR dari mutasi, bukan jumlah
     * seluruh leg.
     *
     * Menjumlahkan semua leg benar untuk mutasi satu arah (restock/penyesuaian hanya punya
     * satu sisi), tapi MELIPATGANDAKAN mutasi berpasangan — pengeluaran 100 unit tercatat
     * sebagai 200 karena leg OUT dan IN sama-sama ikut dihitung. Hari ini hanya jalur satu
     * arah yang memanggil [withApproval], jadi kesalahannya belum terlihat; begitu
     * ISSUE_EXCEPTION dibuka lewat endpoint, setiap pengeluaran akan melompati ambang dua kali
     * lebih cepat dari yang disetel tenant. Ditutup sekarang, selagi murah.
     */
    private fun approvalAmount(movement: InventoryMovement): Long {
        val inbound = movement.legs.filter { it.direction == LegDirection.IN }.sumOf { it.quantity.toLong() }
        val outbound = movement.legs.filter { it.direction == LegDirection.OUT }.sumOf { it.quantity.toLong() }
        return maxOf(inbound, outbound)
    }

    /**
     * Terjemahkan kegagalan saldo jadi 409 yang bisa dibaca operator.
     *
     * [InventoryInsufficientBalance] adalah `IllegalStateException` sehingga tanpa ini ia
     * jatuh ke penangan generik dan muncul sebagai 500 "kesalahan server" — petugas gudang
     * akan mengira sistemnya rusak, bukan bahwa stoknya memang kurang.
     */
    private fun applyLedger(command: MovementCommand): InventoryMovement = try {
        ledger.apply(command)
    } catch (ex: InventoryInsufficientBalance) {
        throw ConflictException("Stok tidak mencukupi untuk mutasi ini")
    }

    private fun requireLocation(tenantId: UUID, locationId: UUID): InventoryLocation =
        locations.findById(locationId)?.takeIf { it.tenantId == tenantId }
            ?: throw NotFoundException("Lokasi gudang tidak ditemukan")

    /**
     * Barang berserial DITOLAK di jalur curah.
     *
     * Aset bernomor seri punya baris sendiri yang statusnya harus ikut berpindah; jalur ini
     * hanya menggerakkan angka saldo. Kalau dibiarkan lewat, saldo akan bilang satu unit
     * hilang sementara daftar aset masih menampilkannya tersedia di rak — dua sumber
     * kebenaran yang saling membantah dan tak satu pun bisa dipercaya.
     */
    private fun requireBulkItem(tenantId: UUID, line: StockLine, operation: String): InventoryItem {
        val item = items.requireActive(line.itemId, tenantId)
        if (item.serialized) throw ValidationException("Item ${item.code} berserial dan tidak bisa lewat $operation curah")
        if (line.quantity <= 0) throw ValidationException("Jumlah harus lebih dari nol")
        return item
    }

    /**
     * Ambil aset yang disebut baris permintaan dan pastikan ia benar-benar ada di tempat dan
     * status yang diklaim. Barang curah mengembalikan daftar kosong.
     */
    private fun resolveAssets(
        tenantId: UUID,
        item: InventoryItem,
        line: StockLine,
        locationId: UUID,
        expected: InventoryStatus,
    ): List<SerializedAsset> {
        if (line.quantity <= 0) throw ValidationException("Jumlah harus lebih dari nol")
        if (!item.serialized) {
            if (line.serialNumbers.isNotEmpty()) throw ValidationException("Item ${item.code} bukan barang berserial")
            return emptyList()
        }
        if (line.serialNumbers.size != line.quantity) {
            throw ValidationException("Item ${item.code} berserial: jumlah nomor seri harus sama dengan kuantitas")
        }
        return line.serialNumbers.map { serial ->
            val asset = assets.findBySerial(tenantId, serial.trim())
                ?: throw NotFoundException("Nomor seri ${serial.trim()} tidak terdaftar")
            if (asset.skuId != item.id) throw ValidationException("Nomor seri ${asset.serialNumber} bukan milik item ${item.code}")
            if (asset.locationId != locationId) throw ConflictException("Nomor seri ${asset.serialNumber} tidak berada di lokasi asal")
            if (asset.status != expected) throw ConflictException("Nomor seri ${asset.serialNumber} berstatus ${asset.status}, bukan $expected")
            asset
        }
    }

    /**
     * Satu leg OUT + satu leg IN per unit (untuk barang berserial) atau per baris (curah).
     * Mutasi berpasangan WAJIB seimbang — lihat invariant di [InventoryMovement].
     */
    private fun legPair(item: InventoryItem, line: StockLine, moved: List<SerializedAsset>, out: LegEnd, into: LegEnd): List<MovementLeg> {
        if (moved.isEmpty()) {
            return listOf(
                MovementLeg(LegDirection.OUT, item.id, item.id, out.locationId, line.quantity, false, out.ownerId, out.ownerKind, out.status),
                MovementLeg(LegDirection.IN, item.id, item.id, into.locationId, line.quantity, false, into.ownerId, into.ownerKind, into.status),
            )
        }
        return moved.flatMap { asset ->
            listOf(
                MovementLeg(LegDirection.OUT, item.id, item.id, out.locationId, 1, true, out.ownerId, out.ownerKind, out.status, asset.id, asset.serialNumber),
                MovementLeg(LegDirection.IN, item.id, item.id, into.locationId, 1, true, into.ownerId, into.ownerKind, into.status, asset.id, asset.serialNumber),
            )
        }
    }

    private data class LegEnd(val locationId: UUID, val ownerId: UUID, val ownerKind: OwnerKind, val status: InventoryStatus)
}

/** Satu baris barang dalam sebuah operasi gudang. */
data class StockLine(
    val itemId: UUID,
    val quantity: Int,
    /** Wajib dan harus sebanyak [quantity] kalau itemnya berserial; kosong kalau curah. */
    val serialNumbers: List<String> = emptyList(),
)

/**
 * Identitas operasi yang dipakai seluruh perintah gudang.
 *
 * [operationKey] + [payloadHash] adalah kunci idempotensi ledger: kunci sama dengan payload
 * berbeda adalah KONFLIK, bukan replay — kalau dilonggarkan, retry klien yang membawa jumlah
 * berbeda diterima diam-diam sebagai "sudah pernah" dan stok tidak pernah bergerak.
 */
interface InventoryOperationCommand {
    val tenantId: UUID
    val actorId: UUID
    val reason: String
    val operationKey: String
    val payloadHash: String
    val lines: List<StockLine>
}

private fun InventoryOperationCommand.toMovement(kind: MovementKind, legs: List<MovementLeg>) = MovementCommand(
    tenantId, actorId, "inventory.operations", operationKey, payloadHash, reason, kind, legs,
)

data class GoodsReceiptCommand(
    override val tenantId: UUID,
    override val actorId: UUID,
    val locationId: UUID,
    /** Pemegang barang di gudang tujuan — menentukan dimensi saldo yang dituju. */
    val custodianId: UUID,
    override val lines: List<StockLine>,
    override val reason: String,
    override val operationKey: String,
    override val payloadHash: String,
    val emergencyReason: String? = null,
) : InventoryOperationCommand

data class TransferCommand(
    override val tenantId: UUID,
    override val actorId: UUID,
    val fromLocationId: UUID,
    val fromCustodianId: UUID,
    val toLocationId: UUID,
    val toCustodianId: UUID,
    override val lines: List<StockLine>,
    override val reason: String,
    override val operationKey: String,
    override val payloadHash: String,
) : InventoryOperationCommand

data class IssueCommand(
    override val tenantId: UUID,
    override val actorId: UUID,
    val fromLocationId: UUID,
    val custodianId: UUID,
    val technicianId: UUID,
    /** Lokasi berjenis VEHICLE/TECHNICIAN yang mewakili van stock teknisi. */
    val technicianLocationId: UUID,
    override val lines: List<StockLine>,
    override val reason: String,
    override val operationKey: String,
    override val payloadHash: String,
) : InventoryOperationCommand

data class ReturnCommand(
    override val tenantId: UUID,
    override val actorId: UUID,
    val fromLocationId: UUID,
    val technicianId: UUID,
    val toLocationId: UUID,
    val custodianId: UUID,
    val quarantine: Boolean = false,
    override val lines: List<StockLine>,
    override val reason: String,
    override val operationKey: String,
    override val payloadHash: String,
) : InventoryOperationCommand

/**
 * Jenis penyesuaian. Dipisah dari [MovementKind] supaya permukaan tulis hanya menawarkan
 * empat pilihan yang memang masuk akal diminta manusia — `MovementKind` penuh berisi jenis
 * internal (REVERSAL, CONSUME, TRANSFER_RECEIPT) yang tidak boleh bisa diminta dari luar.
 */
enum class AdjustmentKind(
    val movementKind: MovementKind,
    val approvalType: InventoryApprovalType,
    val status: InventoryStatus,
) {
    CORRECTION(MovementKind.ADJUSTMENT, InventoryApprovalType.ADJUSTMENT, InventoryStatus.AVAILABLE),
    LOSS(MovementKind.LOSS, InventoryApprovalType.LOSS, InventoryStatus.AVAILABLE),
    SCRAP(MovementKind.SCRAP, InventoryApprovalType.SCRAP, InventoryStatus.AVAILABLE),
    WRITE_OFF(MovementKind.WRITE_OFF, InventoryApprovalType.WRITE_OFF, InventoryStatus.AVAILABLE),
    ;

    init {
        // Penyesuaian adalah cara termudah menutupi kebocoran aset, jadi TIDAK SATU PUN boleh
        // berlaku tanpa mata kedua. Dipasang sebagai invariant, bukan sekadar niat baik: jenis
        // penyesuaian baru yang menunjuk MovementKind tanpa `requiresApproval` akan langsung
        // memotong stok begitu diminta, dan kesalahan sehalus itu tidak akan terlihat di review.
        // Enum init berjalan saat kelas dimuat — aplikasinya gagal start, bukan bocor diam-diam.
        require(movementKind.requiresApproval) {
            "AdjustmentKind $name memakai ${movementKind.name} yang tidak wajib disetujui"
        }
    }
}

data class AdjustmentCommand(
    override val tenantId: UUID,
    override val actorId: UUID,
    val locationId: UUID,
    val custodianId: UUID,
    val kind: AdjustmentKind,
    /** Hanya berlaku untuk [AdjustmentKind.CORRECTION]; jenis lain selalu mengurangi. */
    val increase: Boolean = false,
    override val lines: List<StockLine>,
    override val reason: String,
    override val operationKey: String,
    override val payloadHash: String,
    val emergencyReason: String? = null,
) : InventoryOperationCommand

/**
 * Hasil operasi yang butuh persetujuan: mutasinya sendiri plus permintaan yang menggantungnya.
 * [approval] null berarti operasi ini replay dari permintaan yang sudah diproses.
 */
data class InventoryOperationResult(
    val movement: InventoryMovement,
    val approval: InventoryApprovalRequest?,
)

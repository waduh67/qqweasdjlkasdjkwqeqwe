package com.duluin.ftth.inventory.application.service

import com.duluin.ftth.common.domain.UuidV7
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
 * Pendaftaran aset berserial secara massal — hasil tempel/scan daftar SN+MAC.
 *
 * Satu kiriman ONT berisi puluhan unit dan petugas gudang menempelkan seluruh daftarnya
 * sekaligus. Sebelum ini satu-satunya jalan adalah `InventoryRegistryService.register()`
 * satu per satu, tanpa endpoint sama sekali, sehingga aset serial praktis tidak pernah
 * masuk sistem dan seluruh pelacakan per unit (yang jadi alasan modul ini ada) mati.
 */
@Service
class InventoryAssetRegistrationService(
    private val assets: SerializedAssetRepository,
    private val locations: InventoryLocationRepository,
    private val items: InventoryItemService,
    private val ledger: InventoryMovementLedgerService,
    /**
     * Opsional dengan default null agar unit test lama yang membangun service ini secara
     * posisional tetap hidup. Jalur restock berserial MELEDAK kalau bean-nya tidak ada, bukan
     * diam-diam melewati approval — lihat [requestRestock].
     */
    private val approvals: InventoryApprovalService? = null,
) {

    /**
     * Barang berserial yang SUDAH ada di tangan gudang: didaftarkan dan langsung jadi stok.
     */
    @Transactional
    fun registerBulk(command: BulkSerialRegistration): BulkSerialRegistrationResult =
        intake(command, SerialIntake.RECEIPT)

    /**
     * Barang berserial yang BARU DIJANJIKAN pemasok: unitnya didaftarkan sekarang, tapi
     * saldonya belum bergerak sampai kirimannya disetujui.
     *
     * Kenapa asetnya didaftarkan lebih dulu, bukan nanti setelah approval:
     *
     * - Pemeriksaan duplikat SN/MAC HANYA ada di pendaftaran ([rejectDuplicates]). Kalau
     *   daftar serialnya baru menyentuh sistem setelah approval, dua pengajuan yang memuat
     *   nomor seri yang sama akan sama-sama lolos dan baru bentrok di detik terakhir — di
     *   tangan approver, yang tidak punya cara memperbaikinya selain menolak semuanya.
     * - Daftar serial adalah ISI permintaan yang sedang dinilai. Menyimpannya di tabel aset
     *   membuat approver bisa melihat unit apa saja yang akan masuk; menunda pembuatannya
     *   berarti isi permintaan itu hanya hidup di payload klien.
     *
     * Statusnya [InventoryStatus.AWAITING_RECEIPT] — terdaftar, tapi BUKAN stok. Mendaftarkan
     * langsung sebagai AVAILABLE membuat gudang mengaku punya barang yang belum pernah datang,
     * dan itu persis kebohongan yang membuat restock harus lewat persetujuan sejak awal.
     */
    @Transactional
    fun requestRestock(command: BulkSerialRegistration): BulkSerialRegistrationResult {
        val approvalService = approvals ?: throw IllegalStateException("inventory approval service is not configured")
        val result = intake(command, SerialIntake.RESTOCK)
        val movement = ledger.movement(result.movementId)
        // Replay: mutasinya sudah pernah diajukan, permintaan persetujuannya sudah ada (atau
        // sudah diputuskan). Membuatnya lagi hanya akan bentrok di kunci operasi.
        if (result.replayed || movement == null || movement.state != MovementState.PENDING_APPROVAL) return result
        val approval = approvalService.request(
            CreateInventoryApproval(
                command.tenantId, InventoryApprovalType.RESTOCK, result.registered.size.toLong(),
                command.actorId, command.custodyOwnerId, "${command.operationKey}:approval",
                command.payloadHash, result.movementId, command.emergencyReason,
            ),
        )
        return result.copy(approval = approval)
    }

    private fun intake(command: BulkSerialRegistration, intake: SerialIntake): BulkSerialRegistrationResult {
        if (command.lines.isEmpty()) throw ValidationException("Daftar serial tidak boleh kosong")
        if (command.lines.size > MAX_BATCH) throw ValidationException("Maksimal $MAX_BATCH serial per pendaftaran")

        // Replay diperiksa lewat LEDGER, bukan lewat `last_operation_key` aset: kolom itu
        // UNIQUE per tenant sehingga hanya SATU dari sekian puluh aset dalam satu batch yang
        // bisa memegangnya. Kalau idempotensi digantungkan ke sana, pengiriman ulang batch
        // yang sama akan mendaftarkan sisanya lagi dan gagal dengan duplikat serial —
        // petugas melihat error padahal yang salah adalah cara kita mengingat operasinya.
        ledger.movementByOperation(command.tenantId, NAMESPACE, command.operationKey)?.let { prior ->
            return BulkSerialRegistrationResult(
                prior.movementId,
                prior.legs.mapNotNull { leg -> leg.assetId?.let { RegisteredSerial(it, leg.serialNumber.orEmpty()) } },
                replayed = true,
            )
        }

        val item = items.requireActive(command.itemId, command.tenantId)
        if (!item.serialized) throw ValidationException("Item ${item.code} bukan barang berserial")
        val location = locations.findById(command.locationId)?.takeIf { it.tenantId == command.tenantId }
            ?: throw NotFoundException("Lokasi gudang tidak ditemukan")

        val normalized = command.lines.map { NormalizedLine(it.serialNumber.trim(), it.macAddress?.trim()?.uppercase()?.ifEmpty { null }) }
        rejectDuplicates(command.tenantId, item, normalized)

        val registered = normalized.map { line ->
            val asset = assets.save(
                SerializedAsset(
                    UuidV7.generate(), command.tenantId,
                    // `skuId` diisi id item: sejak `Sku` dihapus dari domain, master data
                    // barang HANYA `inventory_item`, dan membiarkan sku_id berisi UUID lain
                    // akan menghidupkan lagi id yang tak bisa diterjemahkan siapa pun.
                    item.id, line.serialNumber, line.macAddress, intake.assetStatus,
                    location.id, CustodyClaim(command.custodyOwnerId, OwnerKind.WAREHOUSE, location.id),
                ),
            )
            RegisteredSerial(asset.id, asset.serialNumber)
        }

        // Ledger ikut ditulis, bukan hanya baris aset: tanpa mutasi RECEIVE, saldo kuantitas
        // akan bilang 0 sementara daftar aset berisi 40 unit, dan tidak ada baris mana pun
        // yang bisa menjelaskan dari mana selisihnya datang.
        //
        // Status leg SELALU AVAILABLE, termasuk untuk restock — leg adalah janji tentang
        // dimensi saldo yang akan terisi KALAU mutasinya berlaku, dan AWAITING_RECEIPT SENGAJA
        // tidak pernah punya baris saldo (lihat [InventoryStatus.AWAITING_RECEIPT]). Selama
        // mutasinya PENDING_APPROVAL leg ini belum dibukukan sama sekali, jadi tidak ada saldo
        // yang berbohong.
        val movement = ledger.apply(
            MovementCommand(
                command.tenantId, command.actorId, NAMESPACE, command.operationKey, command.payloadHash,
                command.reason, intake.movementKind,
                registered.map { serial ->
                    MovementLeg(
                        LegDirection.IN, item.id, item.id, location.id, 1, true,
                        command.custodyOwnerId, OwnerKind.WAREHOUSE, InventoryStatus.AVAILABLE,
                        serial.assetId, serial.serialNumber,
                    )
                },
            ),
        )
        return BulkSerialRegistrationResult(movement.movementId, registered, replayed = false)
    }

    /**
     * Tolak SELURUH batch kalau ada satu baris pun yang bentrok.
     *
     * Menerima sebagian terdengar lebih ramah, tapi hasilnya justru jebakan: petugas
     * memperbaiki daftarnya lalu menempel ulang seluruhnya, dan kali ini baris yang tadi
     * berhasil ikut ditolak sebagai "sudah terdaftar". Ia akan mengira pendaftarannya gagal
     * total dan mencari-cari unit yang sebenarnya sudah ada di sistem. Semua-atau-tidak
     * membuat keadaan setelah kegagalan persis sama dengan sebelum ia menekan tombol.
     */
    private fun rejectDuplicates(tenantId: UUID, item: InventoryItem, lines: List<NormalizedLine>) {
        val rejected = linkedMapOf<String, String>()
        val seenSerial = mutableSetOf<String>()
        val seenMac = mutableSetOf<String>()
        lines.forEach { line ->
            when {
                line.serialNumber.isEmpty() -> rejected["(kosong)"] = "nomor seri kosong"
                !seenSerial.add(line.serialNumber) -> rejected[line.serialNumber] = "dobel di dalam daftar"
                assets.existsHistoricalSerial(tenantId, line.serialNumber) -> rejected[line.serialNumber] = "sudah terdaftar"
            }
            if (item.trackMac && line.macAddress == null) {
                rejected[line.serialNumber] = "MAC wajib untuk item ini"
            }
            if (!item.trackMac && line.macAddress != null) {
                rejected[line.serialNumber] = "item ${item.code} tidak melacak MAC"
            }
            val mac = line.macAddress ?: return@forEach
            when {
                !seenMac.add(mac) -> rejected[line.serialNumber] = "MAC $mac dobel di dalam daftar"
                assets.existsHistoricalMac(tenantId, mac) -> rejected[line.serialNumber] = "MAC $mac sudah terdaftar"
            }
        }
        if (rejected.isEmpty()) return
        val detail = rejected.entries.take(MAX_REPORTED).joinToString(", ") { "${it.key} (${it.value})" }
        val extra = if (rejected.size > MAX_REPORTED) " dan ${rejected.size - MAX_REPORTED} lainnya" else ""
        throw ConflictException("Pendaftaran dibatalkan, serial bermasalah: $detail$extra")
    }

    private data class NormalizedLine(val serialNumber: String, val macAddress: String?)

    /**
     * Dua cara barang berserial masuk sistem, dibedakan HANYA oleh dua hal yang memang berbeda:
     * jenis mutasinya dan status awal barisnya. Sisanya (validasi duplikat, normalisasi, replay,
     * penulisan leg) sengaja satu jalur — begitu keduanya punya salinan kodenya sendiri, salah
     * satu pasti tertinggal saat aturan duplikat berubah.
     */
    private enum class SerialIntake(val movementKind: MovementKind, val assetStatus: InventoryStatus) {
        RECEIPT(MovementKind.RECEIVE, InventoryStatus.AVAILABLE),
        RESTOCK(MovementKind.RESTOCK, InventoryStatus.AWAITING_RECEIPT),
    }

    private companion object {
        const val NAMESPACE = "inventory.asset.register"
        /** Batas praktis satu tempelan: di atas ini permintaannya hampir pasti salah tempel. */
        const val MAX_BATCH = 500
        const val MAX_REPORTED = 10
    }
}

data class SerialRegistrationLine(val serialNumber: String, val macAddress: String? = null)

data class BulkSerialRegistration(
    val tenantId: UUID,
    val actorId: UUID,
    val itemId: UUID,
    val locationId: UUID,
    val custodyOwnerId: UUID,
    val lines: List<SerialRegistrationLine>,
    val reason: String,
    val operationKey: String,
    val payloadHash: String,
    /** Hanya dipakai jalur restock; pendaftaran biasa memang tidak lewat persetujuan. */
    val emergencyReason: String? = null,
)

data class RegisteredSerial(val assetId: UUID, val serialNumber: String)

data class BulkSerialRegistrationResult(
    val movementId: UUID,
    val registered: List<RegisteredSerial>,
    val replayed: Boolean,
    /**
     * Terisi hanya untuk permintaan restock berserial yang baru lahir. Null berarti unitnya
     * sudah jadi stok (pendaftaran biasa) atau permintaannya replay.
     */
    val approval: InventoryApprovalRequest? = null,
)

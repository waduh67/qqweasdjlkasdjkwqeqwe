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
) {

    @Transactional
    fun registerBulk(command: BulkSerialRegistration): BulkSerialRegistrationResult {
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
                    item.id, line.serialNumber, line.macAddress, InventoryStatus.AVAILABLE,
                    location.id, CustodyClaim(command.custodyOwnerId, OwnerKind.WAREHOUSE, location.id),
                ),
            )
            RegisteredSerial(asset.id, asset.serialNumber)
        }

        // Ledger ikut ditulis, bukan hanya baris aset: tanpa mutasi RECEIVE, saldo kuantitas
        // akan bilang 0 sementara daftar aset berisi 40 unit, dan tidak ada baris mana pun
        // yang bisa menjelaskan dari mana selisihnya datang.
        val movement = ledger.apply(
            MovementCommand(
                command.tenantId, command.actorId, NAMESPACE, command.operationKey, command.payloadHash,
                command.reason, MovementKind.RECEIVE,
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
)

data class RegisteredSerial(val assetId: UUID, val serialNumber: String)

data class BulkSerialRegistrationResult(
    val movementId: UUID,
    val registered: List<RegisteredSerial>,
    val replayed: Boolean,
)

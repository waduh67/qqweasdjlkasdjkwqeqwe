package com.duluin.ftth.inventory.domain.model

import com.duluin.ftth.common.domain.UuidV7
import com.duluin.ftth.common.domain.error.ValidationException
import java.math.BigDecimal
import java.util.UUID

/**
 * Kategori barang gudang. Dipakai untuk mengelompokkan di UI dan menjadi dasar BOM
 * per tipe work order (PSB butuh DROPCORE + ONT + PATCHCORD, dismantle mengembalikannya).
 */
enum class InventoryItemCategory { ONT, DROPCORE, FEEDER, PATCHCORD, ADAPTER, CONNECTOR, ACCESSORY, TOOL, OTHER }

/** Satuan pencatatan stok. METER/ROLL dipakai kabel, PCS/SET untuk barang hitungan. */
enum class InventoryUnit { PCS, METER, ROLL, SET }

/**
 * Master data satu jenis barang gudang — pemilik arti dari `item_id`/`sku_id` yang
 * bertebaran di ledger, saldo, stock opname, dan aset serial.
 *
 * Sebelum agregat ini ada, seluruh id barang di sistem adalah UUID menggantung: tak ada
 * yang tahu barang apa itu, satuannya apa, dan apakah unitnya wajib di-scan satu per satu.
 */
class InventoryItem private constructor(
    val id: UUID,
    val tenantId: UUID,
    val code: String,
    name: String,
    category: InventoryItemCategory,
    val unit: InventoryUnit,
    val serialized: Boolean,
    trackMac: Boolean,
    reorderPoint: BigDecimal?,
    active: Boolean,
) {
    var name: String = name
        private set

    var category: InventoryItemCategory = category
        private set

    var trackMac: Boolean = trackMac
        private set

    var reorderPoint: BigDecimal? = reorderPoint
        private set

    var active: Boolean = active
        private set

    companion object {
        /**
         * Kode diketik dan di-scan manusia, jadi dibatasi huruf besar/angka/pemisah.
         * Huruf kecil dan spasi DILARANG supaya "ONT-01" dan "ont-01 " tidak pernah jadi
         * dua baris berbeda yang saldonya terbelah diam-diam.
         */
        private val CODE = Regex("[A-Z0-9][A-Z0-9._-]{1,63}")

        fun create(
            tenantId: UUID,
            code: String,
            name: String,
            category: InventoryItemCategory,
            unit: InventoryUnit,
            serialized: Boolean,
            trackMac: Boolean = false,
            reorderPoint: BigDecimal? = null,
        ): InventoryItem {
            val normalizedCode = code.trim().uppercase()
            if (!CODE.matches(normalizedCode)) throw ValidationException("Kode item tidak valid: hanya huruf besar, angka, titik, garis bawah, dan strip (2–64 karakter)")
            if (name.isBlank()) throw ValidationException("Nama item wajib diisi")
            // MAC hanya punya arti untuk barang yang dilacak per unit. Tanpa aturan ini,
            // dropcore satuan METER bisa ditandai "lacak MAC" dan pendaftaran aset massal
            // akan menuntut MAC untuk barang yang tidak punya MAC sama sekali.
            if (trackMac && !serialized) throw ValidationException("Lacak MAC hanya berlaku untuk item berserial")
            // Barang berserial dihitung per unit fisik; satuan METER/ROLL berarti barang
            // curah yang dipotong sesuai kebutuhan sehingga tidak bisa punya nomor seri.
            if (serialized && unit != InventoryUnit.PCS && unit != InventoryUnit.SET) {
                throw ValidationException("Item berserial harus bersatuan PCS atau SET")
            }
            if (reorderPoint != null && reorderPoint.signum() < 0) throw ValidationException("Titik pemesanan ulang tidak boleh negatif")
            return InventoryItem(
                UuidV7.generate(), tenantId, normalizedCode, name.trim(), category, unit,
                serialized, trackMac, reorderPoint, true,
            )
        }

        fun rehydrate(
            id: UUID,
            tenantId: UUID,
            code: String,
            name: String,
            category: InventoryItemCategory,
            unit: InventoryUnit,
            serialized: Boolean,
            trackMac: Boolean,
            reorderPoint: BigDecimal?,
            active: Boolean,
        ) = InventoryItem(id, tenantId, code, name, category, unit, serialized, trackMac, reorderPoint, active)
    }

    /**
     * Perubahan deskriptif saja. [serialized] SENGAJA tidak bisa diubah setelah dibuat:
     * mengubahnya di tengah jalan membuat leg ledger lama (yang wajib/tidak wajib punya
     * `asset_id` menurut flag lama) tidak lagi konsisten dengan flag barunya, dan saldo
     * per unit tidak bisa direkonstruksi dari catatan curah.
     */
    fun describe(name: String, category: InventoryItemCategory, reorderPoint: BigDecimal?) {
        if (name.isBlank()) throw ValidationException("Nama item wajib diisi")
        if (reorderPoint != null && reorderPoint.signum() < 0) throw ValidationException("Titik pemesanan ulang tidak boleh negatif")
        this.name = name.trim()
        this.category = category
        this.reorderPoint = reorderPoint
    }

    fun trackMac(enabled: Boolean) {
        if (enabled && !serialized) throw ValidationException("Lacak MAC hanya berlaku untuk item berserial")
        this.trackMac = enabled
    }

    /**
     * Item dinonaktifkan, BUKAN dihapus: ledger dan aset lama tetap menunjuk id ini, dan
     * menghapusnya akan membuat riwayat mutasi kehilangan nama barangnya.
     */
    fun deactivate() {
        active = false
    }

    fun activate() {
        active = true
    }
}

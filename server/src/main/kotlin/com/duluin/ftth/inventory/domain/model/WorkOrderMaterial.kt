package com.duluin.ftth.inventory.domain.model

import com.duluin.ftth.common.domain.UuidV7
import com.duluin.ftth.common.domain.error.ConflictException
import com.duluin.ftth.common.domain.error.ValidationException
import java.time.Instant
import java.util.UUID

/**
 * Nasib satu unit berserial yang sudah keluar gudang untuk sebuah work order.
 *
 * Ketiganya WAJIB dideklarasikan — tidak ada pilihan "tidak tahu". Unit yang sudah dipegang
 * teknisi tapi tidak pernah dilaporkan nasibnya adalah kebocoran aset yang paling sulit
 * dilihat: saldo teknisi terus menggelembung, dan setiap opname akan menyalahkan orang yang
 * berbeda-beda.
 */
enum class MaterialOutcome {
    /** Terpasang di pelanggan. Inilah satu-satunya nasib yang MEMOTONG saldo saat WO disetujui. */
    INSTALLED,

    /** Dibawa pulang utuh. Masih harus benar-benar diterima gudang lewat retur biasa. */
    RETURNED,

    /** Hilang atau rusak di lapangan. Harus lewat penyesuaian stok yang butuh persetujuan. */
    LOST,
}

/**
 * Satu unit berserial yang di-scan teknisi saat menyelesaikan work order.
 *
 * [serialNumber] dan [macAddress] SENGAJA disalin dari asetnya, bukan di-join belakangan:
 * ini catatan historis "apa yang terpasang di rumah pelanggan hari itu", dan tidak boleh
 * ikut berubah kalau baris asetnya kelak diperbaiki petugas master data.
 */
data class WorkOrderMaterialSerial(
    val id: UUID,
    val tenantId: UUID,
    val materialId: UUID,
    val workOrderId: UUID,
    val assetId: UUID,
    val serialNumber: String,
    val macAddress: String?,
    val outcome: MaterialOutcome,
    val scannedAt: Instant,
    val scannedBy: UUID,
) {
    init {
        require(serialNumber.isNotBlank()) { "nomor seri wajib diisi" }
    }
}

/**
 * Satu baris BOM: berapa banyak item X biasanya dipakai untuk jenis WO Y.
 *
 * BUKAN pembatas. Lihat komentar panjang di V186 — template hanya MEMPRA-ISI rencana, dan
 * yang dijaga sistem adalah selisihnya terlihat, bukan selisihnya dilarang.
 */
data class WorkOrderMaterialTemplateLine(
    val id: UUID,
    val tenantId: UUID,
    val workOrderType: String,
    val itemId: UUID,
    val plannedQuantity: Int,
    val note: String? = null,
) {
    init {
        require(workOrderType.isNotBlank()) { "jenis work order wajib diisi" }
        require(plannedQuantity > 0) { "jumlah template harus lebih dari nol" }
    }
}

/**
 * Rencana dan realisasi satu item pada satu work order.
 *
 * Invariant kuantitasnya adalah CERMIN dari CHECK `work_order_material_realized_ck` di V186.
 * Diduplikasi di domain SENGAJA: kegagalannya lebih berguna sebagai pesan Indonesia yang
 * bisa dibaca dispatcher daripada sebagai `ConstraintViolationException` mentah yang baru
 * meledak jauh di dalam flush Hibernate — di sana konteks "baris mana, item apa" sudah hilang.
 */
data class WorkOrderMaterialLine(
    val id: UUID,
    val tenantId: UUID,
    val workOrderId: UUID,
    val workOrderType: String,
    val itemId: UUID,
    val customerId: UUID,
    val itemCategory: String,
    val serialized: Boolean,
    val templateQuantity: Int?,
    val plannedQuantity: Int,
    val issuedQuantity: Int = 0,
    val usedQuantity: Int = 0,
    val returnedQuantity: Int = 0,
    val lostQuantity: Int = 0,
    val technicianId: UUID? = null,
    val technicianLocationId: UUID? = null,
    val varianceReason: String? = null,
    val serials: List<WorkOrderMaterialSerial> = emptyList(),
) {
    init {
        require(plannedQuantity >= 0 && issuedQuantity >= 0) { "jumlah material tidak boleh negatif" }
        require(usedQuantity >= 0 && returnedQuantity >= 0 && lostQuantity >= 0) { "jumlah material tidak boleh negatif" }
        require(usedQuantity + returnedQuantity + lostQuantity <= issuedQuantity) {
            "realisasi material melebihi yang dikeluarkan gudang"
        }
        require(issuedQuantity == 0 || (technicianId != null && technicianLocationId != null)) {
            "material yang sudah keluar gudang wajib punya pemegang"
        }
    }

    /** Ubah rencana. Tidak menyentuh realisasi — barang yang sudah keluar tidak bisa "direncanakan ulang". */
    fun replan(quantity: Int, template: Int?): WorkOrderMaterialLine {
        if (quantity < 0) throw ValidationException("Jumlah rencana tidak boleh negatif")
        if (quantity < issuedQuantity) {
            throw ConflictException("Rencana tidak bisa dikecilkan di bawah $issuedQuantity unit yang sudah dikeluarkan gudang")
        }
        return copy(plannedQuantity = quantity, templateQuantity = template ?: templateQuantity)
    }

    /**
     * Catat bahwa gudang benar-benar mengeluarkan [quantity] unit ke teknisi.
     *
     * Dipanggil SETELAH mutasi ISSUE berhasil, bukan sebelumnya: kalau angka ini naik lebih
     * dulu lalu mutasinya ditolak karena stok kurang, WO akan mengaku memegang barang yang
     * masih di rak.
     */
    fun markIssued(quantity: Int, technician: UUID, technicianLocation: UUID): WorkOrderMaterialLine {
        if (quantity <= 0) throw ValidationException("Jumlah pengeluaran harus lebih dari nol")
        if (technicianId != null && technicianId != technician) {
            // Satu baris material = satu dimensi saldo. Dua teknisi pada baris yang sama
            // membuat pemotongan saat approval mendarat di saldo orang yang salah, dan yang
            // satunya lagi tersisa stok hantu yang tidak pernah bisa diretur.
            throw ConflictException("Material item ini sudah dikeluarkan ke teknisi lain; buat baris terpisah")
        }
        return copy(
            issuedQuantity = issuedQuantity + quantity,
            technicianId = technician,
            technicianLocationId = technicianLocation,
        )
    }

    /**
     * Catat pemakaian barang CURAH (kabel, konektor, aksesori).
     *
     * Barang berserial DITOLAK di sini: jumlahnya diturunkan dari nomor seri yang di-scan,
     * bukan diketik. Kalau angkanya boleh diketik, "3 ONT terpasang" bisa berdiri tanpa satu
     * pun SN — dan saat pelanggan komplain, tidak ada yang bisa menjawab unit mana yang ada
     * di rumahnya.
     */
    fun recordBulkUsage(used: Int, returned: Int, lost: Int, reason: String?): WorkOrderMaterialLine {
        if (serialized) throw ValidationException("Item berserial: pemakaian dicatat lewat scan nomor seri, bukan diketik")
        if (used < 0 || returned < 0 || lost < 0) throw ValidationException("Jumlah pemakaian tidak boleh negatif")
        if (used + returned + lost > issuedQuantity) {
            throw ConflictException("Total realisasi ${used + returned + lost} melebihi $issuedQuantity unit yang dikeluarkan gudang")
        }
        return copy(
            usedQuantity = used,
            returnedQuantity = returned,
            lostQuantity = lost,
            varianceReason = reason?.trim()?.takeIf { it.isNotEmpty() } ?: varianceReason,
        )
    }

    /** Alasan selisih boleh diisi untuk baris apa pun, termasuk yang berserial. */
    fun explainVariance(reason: String): WorkOrderMaterialLine {
        if (reason.isBlank()) throw ValidationException("Alasan selisih tidak boleh kosong")
        return copy(varianceReason = reason.trim())
    }

    /**
     * Tempelkan hasil scan satu unit berserial dan turunkan ulang kuantitas realisasinya.
     *
     * Kuantitas SELALU dihitung ulang dari daftar serial, tidak pernah ditambah-kurangi
     * secara inkremental: penambahan inkremental akan melenceng diam-diam begitu satu scan
     * dikoreksi (mis. INSTALLED diubah jadi LOST), dan angka yang melenceng itulah yang
     * nanti dipakai memotong saldo.
     */
    fun attachSerial(serial: WorkOrderMaterialSerial): WorkOrderMaterialLine {
        if (!serialized) throw ValidationException("Item curah tidak punya nomor seri")
        if (serial.assetId !in serials.map { it.assetId } && serials.size >= issuedQuantity) {
            throw ConflictException("Sudah $issuedQuantity unit ter-scan, sebanyak yang dikeluarkan gudang")
        }
        val merged = serials.filterNot { it.assetId == serial.assetId } + serial
        return copy(
            serials = merged,
            usedQuantity = merged.count { it.outcome == MaterialOutcome.INSTALLED },
            returnedQuantity = merged.count { it.outcome == MaterialOutcome.RETURNED },
            lostQuantity = merged.count { it.outcome == MaterialOutcome.LOST },
        )
    }

    /** Unit berserial yang sudah keluar gudang tapi belum dilaporkan nasibnya. */
    val unscannedQuantity: Int get() = if (serialized) issuedQuantity - serials.size else 0

    /**
     * Penjaga penyelesaian WO. Melempar kalau baris ini belum layak ditutup.
     *
     * [itemLabel] hanya untuk pesan — teknisi di lapangan butuh tahu ITEM MANA yang kurang,
     * bukan uuid barisnya.
     */
    fun assertReadyForCompletion(itemLabel: String) {
        if (serialized) {
            if (unscannedQuantity > 0) {
                throw ValidationException(
                    "$itemLabel: $unscannedQuantity dari $issuedQuantity unit belum di-scan nomor serinya",
                )
            }
        } else if (usedQuantity + returnedQuantity + lostQuantity != issuedQuantity) {
            val sisa = issuedQuantity - (usedQuantity + returnedQuantity + lostQuantity)
            throw ValidationException("$itemLabel: $sisa unit belum dilaporkan terpakai, dikembalikan, atau hilang")
        }
        if (usedQuantity != plannedQuantity && varianceReason.isNullOrBlank()) {
            throw ValidationException(
                "$itemLabel: pemakaian $usedQuantity berbeda dari rencana $plannedQuantity — alasan selisih wajib diisi",
            )
        }
    }

    companion object {
        fun plan(
            tenantId: UUID,
            workOrderId: UUID,
            workOrderType: String,
            item: InventoryItem,
            customerId: UUID,
            quantity: Int,
            templateQuantity: Int?,
        ): WorkOrderMaterialLine {
            if (quantity < 0) throw ValidationException("Jumlah rencana tidak boleh negatif")
            return WorkOrderMaterialLine(
                id = UuidV7.generate(),
                tenantId = tenantId,
                workOrderId = workOrderId,
                workOrderType = workOrderType,
                itemId = item.id,
                customerId = customerId,
                itemCategory = item.category.name,
                serialized = item.serialized,
                templateQuantity = templateQuantity,
                plannedQuantity = quantity,
            )
        }
    }
}

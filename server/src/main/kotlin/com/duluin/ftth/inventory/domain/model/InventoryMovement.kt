package com.duluin.ftth.inventory.domain.model

import java.time.Instant
import java.util.UUID

/**
 * Bentuk leg yang sah untuk satu jenis mutasi.
 *
 * Dulu aturan ini ditulis sebagai daftar pengecualian di dalam `require` ("semua jenis
 * WAJIB seimbang KECUALI A, B, C..."). Bentuk itu gagal diam-diam saat jenis baru lahir:
 * RESTOCK tidak ikut terdaftar, padahal barang yang dijanjikan datang memang hanya punya
 * leg IN — akibatnya SETIAP permintaan restock ditolak invariant-nya sendiri. LOSS, SCRAP,
 * dan WRITE_OFF menyimpan bom yang sama. Sebagai properti enum, jenis mutasi baru TIDAK
 * BISA ditambahkan tanpa menyatakan bentuknya.
 */
enum class LegShape {
    /**
     * Stok berpindah DI DALAM sistem: jumlah leg IN dan OUT wajib sama. Barang yang keluar
     * dari satu dimensi saldo harus muncul di dimensi lain, kalau tidak mutasi "pindah rak"
     * diam-diam berubah jadi mutasi yang memusnahkan stok.
     */
    PAIRED,

    /**
     * Stok MENYEBERANGI batas sistem — datang dari pemasok, atau lenyap jadi susut/hapus buku.
     * Leg satu arah SAH di sini. Bentuk berpasangan tetap diterima (retur teknisi memakai
     * sepasang leg) karena aturannya hanya melarang yang timpang, bukan mewajibkan timpang.
     */
    BOUNDARY,
}

/**
 * Jenis mutasi stok, lengkap dengan dua sifat yang menentukan cara sistem memperlakukannya:
 * bentuk leg-nya ([legShape]) dan apakah ia butuh mata kedua ([requiresApproval]).
 *
 * Keduanya SENGAJA menempel di sini, bukan tersebar jadi `setOf(...)` di service: dua daftar
 * terpisah yang sama-sama dikunci pada enum ini pasti akan menyimpang begitu ada jenis baru,
 * dan penyimpangannya baru ketahuan saat petugas gudang gagal menyimpan transaksi.
 */
enum class MovementKind(
    val legShape: LegShape,
    val requiresApproval: Boolean,
    /**
     * Status yang WAJIB dipakai baris aset berserial begitu mutasi ini benar-benar berlaku —
     * atau null kalau ledger tidak boleh menyentuh aset sama sekali.
     *
     * Null BUKAN berarti "aset tidak berpindah". Untuk ISSUE/TRANSFER/RETURN, perpindahan
     * asetnya sudah dikerjakan `InventoryOperationsService` di transaksi yang sama karena
     * mutasi-mutasi itu berlaku SEKETIKA; memindahkannya sekali lagi di sini akan menabrak
     * tabel transisi ("invalid inventory transition ISSUED -> ISSUED") dan menggagalkan
     * pengeluaran barang yang sebenarnya sudah benar.
     *
     * Yang terdaftar di sini justru yang TIDAK berlaku seketika: keempatnya menunggu
     * persetujuan. Di sana status aset TIDAK BOLEH berpindah saat permintaan diajukan — kalau
     * ONT sudah ditandai DISPOSED begitu formulir hapus buku dikirim, penolakan approval
     * meninggalkan unit sehat yang tidak bisa dikeluarkan lagi oleh siapa pun. Jadi ledger yang
     * memindahkannya, TEPAT saat leg-nya dibukukan, di transaksi yang sama: saldo dan status
     * aset tidak punya celah untuk berbeda arah.
     */
    val settlesSerialTo: InventoryStatus? = null,
) {
    /** Barang dijanjikan masuk dari pemasok — leg IN saja, dan wajib disetujui dulu. */
    RESTOCK(LegShape.BOUNDARY, requiresApproval = true, settlesSerialTo = InventoryStatus.AVAILABLE),

    /** Barang benar-benar diterima di gudang. Leg IN saja. */
    RECEIVE(LegShape.BOUNDARY, requiresApproval = false),

    RESERVE(LegShape.PAIRED, requiresApproval = false),
    RELEASE(LegShape.PAIRED, requiresApproval = false),
    ISSUE(LegShape.PAIRED, requiresApproval = false),

    /** Pengeluaran di luar pagu/di luar prosedur — pindahnya berpasangan, tapi wajib disetujui. */
    ISSUE_EXCEPTION(LegShape.PAIRED, requiresApproval = true),

    TRANSFER(LegShape.PAIRED, requiresApproval = false),
    TRANSFER_RECEIPT(LegShape.PAIRED, requiresApproval = false),

    /** Retur teknisi. BOUNDARY karena barang bisa kembali tanpa jejak pengeluaran yang cocok. */
    RETURN(LegShape.BOUNDARY, requiresApproval = false),

    REPAIR(LegShape.BOUNDARY, requiresApproval = false),
    QUARANTINE(LegShape.BOUNDARY, requiresApproval = false),

    /** Koreksi stok. Bisa menambah maupun mengurangi, jadi selalu lewat persetujuan. */
    ADJUSTMENT(LegShape.BOUNDARY, requiresApproval = true),

    /** Barang hilang. Unit berserial berakhir LOST — masih bisa ditutup jadi DISPOSED nanti. */
    LOSS(LegShape.BOUNDARY, requiresApproval = true, settlesSerialTo = InventoryStatus.LOST),

    /** Barang rusak dan dimusnahkan; DISPOSED bersifat terminal. */
    SCRAP(LegShape.BOUNDARY, requiresApproval = true, settlesSerialTo = InventoryStatus.DISPOSED),

    /** Hapus buku. Sama terminalnya dengan SCRAP; yang membedakan hanya alasan akuntansinya. */
    WRITE_OFF(LegShape.BOUNDARY, requiresApproval = true, settlesSerialTo = InventoryStatus.DISPOSED),
    COUNT_VARIANCE(LegShape.BOUNDARY, requiresApproval = true),

    DISPOSAL(LegShape.BOUNDARY, requiresApproval = false),
    CONSUME(LegShape.BOUNDARY, requiresApproval = false),

    /**
     * Pembalik mutasi lain. BOUNDARY karena bentuknya MENGIKUTI mutasi yang dibalik: membalik
     * penerimaan barang menghasilkan leg OUT saja, dan memaksanya berpasangan membuat
     * penerimaan yang salah input jadi mustahil dikoreksi.
     */
    REVERSAL(LegShape.BOUNDARY, requiresApproval = false),
}

enum class LegDirection { IN, OUT }
enum class MovementState { APPLIED, PENDING_APPROVAL, FAILED_PERMANENT, REQUIRES_MANUAL_REPAIR }

/**
 * Satu sisi mutasi stok.
 *
 * [assetId] dan [serialNumber] SENGAJA ditambahkan (V174) supaya mutasi kuantitas dan
 * mutasi aset serial berhenti jadi dua pulau terpisah: tanpa keduanya, saldo bisa bilang
 * "3 ONT di gudang" sementara daftar aset bilang 2 dan tidak ada baris mana pun yang bisa
 * menunjukkan unit mana yang hilang.
 */
data class MovementLeg(
    val direction: LegDirection,
    val itemId: UUID,
    val skuId: UUID,
    val locationId: UUID,
    val quantity: Int,
    val serialized: Boolean,
    val custodyOwnerId: UUID,
    val custodyOwnerKind: OwnerKind,
    val status: InventoryStatus,
    val assetId: UUID? = null,
    /**
     * Disalin dari aset saat mutasi terjadi, bukan di-join belakangan: ledger adalah catatan
     * historis dan nomor seri yang tercatat tidak boleh ikut berubah kalau baris asetnya
     * kelak diperbaiki.
     */
    val serialNumber: String? = null,
) {
    init {
        require(quantity > 0) { "movement leg quantity must be positive" }
        require(!serialized || quantity == 1) { "serialized movement quantity must be one" }
        // Cermin dari CHECK `inventory_leg_serial_identity_ck`. Divalidasi di domain juga
        // supaya kegagalan muncul sebagai invariant yang terbaca, bukan error SQL mentah
        // jauh di dalam flush Hibernate.
        require(!serialized || assetId != null) { "mutasi barang berserial WAJIB menyebut aset (assetId)" }
        require(assetId != null || serialNumber == null) { "nomor seri tanpa aset tidak bisa direkonsiliasi" }
    }
}

data class InventoryMovement(
    val movementId: UUID,
    val tenantId: UUID,
    val namespace: String,
    val operationKey: String,
    val payloadHash: String,
    val actorId: UUID,
    val reason: String,
    val serverReceivedAt: Instant,
    val kind: MovementKind,
    val legs: List<MovementLeg>,
    val state: MovementState,
    val compensatesMovementId: UUID? = null,
) {
    init {
        require(namespace.isNotBlank() && operationKey.isNotBlank()) { "operation identity is required" }
        require(payloadHash.isNotBlank()) { "payload hash is required" }
        require(reason.isNotBlank()) { "movement reason is required" }
        require(legs.isNotEmpty()) { "movement must have legs" }
        require(
            kind.legShape != LegShape.PAIRED ||
                legs.count { it.direction == LegDirection.IN } == legs.count { it.direction == LegDirection.OUT },
        ) {
            "paired movement must have balanced IN and OUT legs"
        }
    }
}

data class InventoryBalance(
    val tenantId: UUID,
    val itemId: UUID,
    val skuId: UUID,
    val locationId: UUID,
    val custodyOwnerId: UUID,
    val custodyOwnerKind: OwnerKind,
    val status: InventoryStatus,
    val quantity: Int,
)

data class MovementCommand(
    val tenantId: UUID,
    val actorId: UUID,
    val namespace: String,
    val operationKey: String,
    val payloadHash: String,
    val reason: String,
    val kind: MovementKind,
    val legs: List<MovementLeg>,
    val compensatesMovementId: UUID? = null,
)

class InventoryMovementConflict(message: String) : IllegalArgumentException(message)
class InventoryInsufficientBalance(message: String) : IllegalStateException(message)

package com.duluin.ftth.inventory.domain.model

import java.util.UUID

enum class InventoryStatus {
    AVAILABLE, RESERVED, ISSUED, IN_TRANSIT, CONSUMED, RETURNED, QUARANTINE, LOST, DISPOSED,

    /**
     * Unit sudah TERDAFTAR tapi belum jadi stok: nomor serinya sudah tercatat (dan karena itu
     * sudah ikut diadu dengan seluruh SN/MAC yang pernah ada), namun barangnya belum masuk rak.
     * Dipakai restock berserial yang persetujuannya masih menggantung.
     *
     * Kenapa keadaan ini WAJIB ada, bukan sekadar memakai AVAILABLE dulu lalu dikoreksi:
     * pendaftaran serial adalah satu-satunya tempat duplikat SN/MAC tertangkap, dan itu harus
     * terjadi SEBELUM kiriman disetujui — kalau tidak, duplikatnya baru ketahuan setelah
     * approval selesai dan seluruh batch harus dibatalkan mundur. Tapi mendaftarkannya sebagai
     * AVAILABLE berarti gudang mengaku punya barang yang belum pernah datang, dan kalau
     * restock-nya kemudian DITOLAK, unit hantu itu tetap berdiri di daftar aset sampai ada yang
     * membandingkannya dengan rak fisik berbulan-bulan kemudian.
     *
     * Namanya menyebut apa yang sedang ditunggu (PENERIMAAN barang), bukan apa yang sedang
     * diproses (persetujuan): unit ini tetap belum jadi stok walaupun approval-nya sudah lewat
     * tapi barangnya belum sampai, dan nama seperti `PENDING_APPROVAL` akan berbohong di sana.
     *
     * ATURAN yang menempel padanya: AWAITING_RECEIPT adalah SATU-SATUNYA status aset yang tidak
     * pernah punya baris di proyeksi saldo. Itulah yang membuat saldo dan daftar aset tidak
     * pernah berbeda arah selama masa tunggu — keduanya sama-sama menghitung nol.
     */
    AWAITING_RECEIPT,
}

/**
 * Custody yang WAJIB menyertai sebuah status, atau null kalau statusnya tidak memaksa apa pun.
 *
 * Dipakai dua tempat yang harus sepakat: pembangun leg mutasi (dimensi saldo tujuan) dan
 * [SerializedAsset.settle] (baris asetnya). Kalau keduanya menghitung sendiri-sendiri, saldo
 * bisa mendarat di dimensi `(DISPOSED, WAREHOUSE)` sementara asetnya di `(DISPOSED, DISPOSED)`
 * — dua baris yang tidak akan pernah bisa direkonsiliasi lagi.
 */
fun InventoryStatus.requiredOwnerKind(): OwnerKind? = when (this) {
    InventoryStatus.LOST -> OwnerKind.LOST
    InventoryStatus.DISPOSED -> OwnerKind.DISPOSED
    else -> null
}

enum class LocationKind { WAREHOUSE, BIN, VEHICLE, TECHNICIAN, CUSTOMER_SITE, QUARANTINE, LOST, DISPOSED, TRANSIT }

data class InventoryLocation(
    val id: UUID,
    val tenantId: UUID,
    val code: String,
    val kind: LocationKind,
    /**
     * Gudang induk (V181). Wajib untuk BIN dan harus null untuk WAREHOUSE — aturannya
     * ditegakkan di `InventoryLocationService` karena ia butuh membaca jenis induknya.
     * Tanpa kolom ini, bin adalah pulau yang tidak menempel pada gudang mana pun dan
     * laporan stok per gudang tidak pernah bisa menjumlahkan isi bin-binnya.
     */
    val parentId: UUID? = null,
) {
    init {
        require(code.trim().isNotEmpty()) { "location code is required" }
        require(parentId != id) { "location cannot be its own parent" }
    }
}

// Master data barang gudang sekarang tinggal di `InventoryItem` (tabel `inventory_item`,
// V173). `Sku` dan `Lot` yang dulu di sini DIHAPUS: keduanya tidak punya tabel, tidak punya
// repository, dan tidak pernah direferensikan satu baris kode pun — membiarkannya hanya
// membuat pembaca mengira master data gudang sudah ada padahal belum.

data class CustodyClaim(
    val ownerId: UUID,
    val ownerKind: OwnerKind,
    val locationId: UUID?,
)

enum class OwnerKind { WAREHOUSE, VEHICLE, TECHNICIAN, CUSTOMER, REPAIR, TRANSIT, LOST, DISPOSED }

data class SerializedAsset(
    val id: UUID,
    val tenantId: UUID,
    val skuId: UUID,
    val serialNumber: String,
    val macAddress: String?,
    val status: InventoryStatus,
    val locationId: UUID,
    val custody: CustodyClaim,
    val installedOnuId: UUID? = null,
) {
    init {
        require(serialNumber.trim().isNotEmpty()) { "serial number is required" }
        require(macAddress == null || MAC.matches(macAddress)) { "invalid MAC address" }
        require(custody.locationId == locationId || custody.ownerKind == OwnerKind.TRANSIT) {
            "custody location must match asset location"
        }
        require(status != InventoryStatus.DISPOSED || custody.ownerKind == OwnerKind.DISPOSED) {
            "disposed asset must have disposed custody"
        }
    }

    fun transition(to: InventoryStatus, destination: InventoryLocation, nextCustody: CustodyClaim): SerializedAsset {
        require(destination.tenantId == tenantId) { "destination belongs to another tenant" }
        require(nextCustody.locationId == destination.id || nextCustody.ownerKind == OwnerKind.TRANSIT) {
            "custody does not claim destination"
        }
        require(isAllowed(status, to)) { "invalid inventory transition $status -> $to" }
        require(to != InventoryStatus.IN_TRANSIT || nextCustody.ownerKind == OwnerKind.TRANSIT) {
            "in-transit asset requires transit custody"
        }
        require(to != InventoryStatus.DISPOSED || nextCustody.ownerKind == OwnerKind.DISPOSED) {
            "disposed asset requires disposed custody"
        }
        return copy(status = to, locationId = destination.id, custody = nextCustody)
    }

    /**
     * Pindah tempat TANPA ganti status — untuk transfer antar gudang/bin.
     *
     * Tidak bisa memakai [transition] karena tabel transisinya menolak AVAILABLE -> AVAILABLE:
     * ia menjaga perubahan STATUS, bukan perpindahan tempat. Kalau transfer dipaksa lewat
     * sana, satu-satunya jalan yang tersisa adalah menurunkan status ke IN_TRANSIT lalu
     * menaikkannya lagi — dua mutasi untuk satu perpindahan nyata, dan aset yang gagal di
     * langkah kedua tersangkut IN_TRANSIT di gudang yang sudah menerimanya secara fisik.
     */
    fun relocate(destination: InventoryLocation, nextCustody: CustodyClaim): SerializedAsset {
        require(destination.tenantId == tenantId) { "destination belongs to another tenant" }
        require(nextCustody.locationId == destination.id || nextCustody.ownerKind == OwnerKind.TRANSIT) {
            "custody does not claim destination"
        }
        // LOST dan AWAITING_RECEIPT ikut ditolak (bukan hanya CONSUMED/DISPOSED): unit yang
        // dinyatakan hilang atau yang barangnya belum datang TIDAK ADA di rak mana pun, jadi
        // "memindahkannya" hanya memindahkan kebohongan ke lokasi lain — dan begitu ia tercatat
        // di gudang tujuan, tidak ada lagi yang ingat bahwa ia sebenarnya tak pernah ditemukan.
        require(
            status != InventoryStatus.CONSUMED && status != InventoryStatus.DISPOSED &&
                status != InventoryStatus.LOST && status != InventoryStatus.AWAITING_RECEIPT,
        ) {
            "consumed, disposed, lost, or awaiting-receipt asset cannot be relocated"
        }
        return copy(locationId = destination.id, custody = nextCustody)
    }

    /**
     * Pindah STATUS tanpa pindah tempat — penghapusbukuan (LOSS/SCRAP/WRITE_OFF) dan penerimaan
     * restock berserial yang akhirnya disetujui.
     *
     * Tidak bisa memakai [transition]: ia menuntut [InventoryLocation] tujuan, padahal unit yang
     * dihapusbukukan TIDAK KE MANA-MANA — ia tetap di rak yang sama, hanya berhenti dihitung
     * sebagai barang sehat. Memaksa jalur itu berarti memuat baris lokasi hanya untuk
     * membuktikan bahwa tujuannya sama dengan asalnya.
     *
     * Custody ikut berpindah kalau statusnya memaksa ([requiredOwnerKind]) — aset DISPOSED yang
     * custody-nya masih WAREHOUSE akan ditolak invariant konstruktornya sendiri.
     */
    fun settle(to: InventoryStatus): SerializedAsset {
        require(isAllowed(status, to)) { "invalid inventory transition $status -> $to" }
        val ownerKind = to.requiredOwnerKind() ?: custody.ownerKind
        return copy(status = to, custody = custody.copy(ownerKind = ownerKind))
    }

    /**
     * Bisakah unit ini dipindahkan ke [to]?
     *
     * Ada supaya permukaan tulis bisa MENOLAK DI MUKA memakai tabel transisi yang SAMA dengan
     * yang dipakai saat mutasinya benar-benar berlaku. Tanpa ini, permintaan hapus buku atas
     * unit yang sudah dihapusbukukan lolos sampai ke antrean approval, lalu meledak berjam-jam
     * kemudian di tangan approver — yang tidak bisa berbuat apa-apa selain menolaknya.
     */
    fun canSettleTo(to: InventoryStatus): Boolean = isAllowed(status, to)

    fun linkInstalledOnu(onuId: UUID): SerializedAsset {
        require(status == InventoryStatus.ISSUED || status == InventoryStatus.CONSUMED) {
            "only issued or consumed assets can be linked to an ONU"
        }
        require(installedOnuId == null || installedOnuId == onuId) { "asset already linked to another ONU" }
        return copy(installedOnuId = onuId)
    }

    companion object {
        private val MAC = Regex("(?i)^[0-9a-f]{2}([-:])[0-9a-f]{2}(\\1[0-9a-f]{2}){4}$")

        /**
         * Keadaan yang masih MEMEGANG barang fisik, jadi masih bisa dihapusbukukan.
         *
         * LOST ikut di sini karena unit yang hilang memang lazim ditutup jadi DISPOSED setelah
         * pencarian dihentikan. Yang TIDAK ikut: CONSUMED dan DISPOSED (sudah terminal —
         * menghapusbukukan dua kali akan memotong saldo dua kali untuk satu unit yang sama) dan
         * AWAITING_RECEIPT (barangnya belum pernah datang; yang batal itu kirimannya, dan
         * jalurnya adalah penolakan restock, bukan penghapusbukuan aset).
         */
        private val WRITE_OFF_SOURCES = setOf(
            InventoryStatus.AVAILABLE, InventoryStatus.RESERVED, InventoryStatus.ISSUED,
            InventoryStatus.IN_TRANSIT, InventoryStatus.RETURNED, InventoryStatus.QUARANTINE,
            InventoryStatus.LOST,
        )

        private fun isAllowed(from: InventoryStatus, to: InventoryStatus): Boolean = when {
            // Tidak ada transisi ke diri sendiri: perpindahan tempat tanpa ganti status punya
            // jalurnya sendiri ([relocate]). Aturan ini juga yang menutup LOST -> LOST.
            from == to -> false
            /*
             * Hapus buku bisa terjadi dari keadaan hidup MANA PUN, bukan hanya dari karantina.
             *
             * Dulu DISPOSED hanya bisa dicapai dari QUARANTINE/LOST. Bentuk itu masuk akal di
             * atas kertas — "periksa dulu, baru musnahkan" — tapi ia membuat kasus paling umum
             * mustahil: ONT yang jelas-jelas hancur di rak (terlindas forklift, kena banjir)
             * harus lebih dulu dikarantina lewat mutasi yang tak pernah benar-benar terjadi,
             * hanya supaya mutasi berikutnya diterima. Mata kedua tetap dijaga di tempat yang
             * benar: LOSS/SCRAP/WRITE_OFF semuanya `requiresApproval = true`.
             */
            to == InventoryStatus.LOST || to == InventoryStatus.DISPOSED -> from in WRITE_OFF_SOURCES
            else -> when (from) {
                InventoryStatus.AVAILABLE -> to in setOf(InventoryStatus.RESERVED, InventoryStatus.ISSUED, InventoryStatus.IN_TRANSIT, InventoryStatus.QUARANTINE)
                InventoryStatus.RESERVED -> to in setOf(InventoryStatus.AVAILABLE, InventoryStatus.ISSUED, InventoryStatus.IN_TRANSIT)
                InventoryStatus.ISSUED -> to in setOf(InventoryStatus.CONSUMED, InventoryStatus.RETURNED, InventoryStatus.QUARANTINE)
                InventoryStatus.IN_TRANSIT -> to in setOf(InventoryStatus.AVAILABLE, InventoryStatus.ISSUED, InventoryStatus.RETURNED, InventoryStatus.QUARANTINE)
                InventoryStatus.RETURNED -> to in setOf(InventoryStatus.AVAILABLE, InventoryStatus.QUARANTINE)
                // Restock berserial yang disetujui: barangnya sampai, unitnya jadi stok. Satu-satunya
                // jalan keluar AWAITING_RECEIPT — kiriman yang DITOLAK tidak berpindah status, barisnya
                // memang dihapus (lihat InventoryMovementLedgerService.releaseSerialAssets).
                InventoryStatus.AWAITING_RECEIPT -> to == InventoryStatus.AVAILABLE
                InventoryStatus.QUARANTINE, InventoryStatus.LOST -> false
                /*
                 * CONSUMED -> RETURNED MEMBUKA KEMBALI STATUS YANG TADINYA TERMINAL. Ditulis
                 * terang-terangan karena itu memang yang terjadi, dan konsekuensinya harus
                 * terlihat oleh siapa pun yang membaca tabel ini.
                 *
                 * Kenapa harus dibuka: ONT yang terpasang di rumah pelanggan berstatus CONSUMED.
                 * Selama CONSUMED benar-benar terminal, WO DISMANTLE TIDAK PUNYA CARA APA PUN
                 * mengembalikan unit itu ke pembukuan — perangkatnya dicabut, dibawa pulang
                 * teknisi, dan selamanya tercatat "terpakai di rumah pelanggan yang sudah
                 * berhenti berlangganan". Aset nyata yang lenyap dari sistem, bukan selisih
                 * yang nanti ketahuan.
                 *
                 * Kenapa ini TIDAK jadi lubang: yang menahan penyalahgunaannya BUKAN tabel ini,
                 * melainkan penjaga di jalur penarikan (lihat V197 dan `WorkOrderAssetRecoveryService`):
                 *   * hanya unit berstatus CONSUMED yang bisa di-scan — unit AVAILABLE di rak
                 *     bukan unit yang terpasang di rumah siapa pun;
                 *   * satu aset hanya boleh ditarik SEKALI, dijaga indeks unik parsial
                 *     `work_order_recovered_asset_active_uq`;
                 *   * saldo baru bergerak setelah WO-nya DISETUJUI orang lain.
                 *
                 * Tanpa ketiganya, transisi ini jadi cara termudah menutupi selisih stok:
                 * "menarik" unit yang sebenarnya sudah terjual, berkali-kali, sampai angka
                 * pembukuan cocok dengan rak.
                 *
                 * Tujuannya SENGAJA hanya RETURNED, bukan langsung AVAILABLE: unitnya baru ada
                 * di tangan teknisi, belum di rak (D3).
                 */
                InventoryStatus.CONSUMED -> to == InventoryStatus.RETURNED
                InventoryStatus.DISPOSED -> false
            }
        }
    }
}

class InventoryInvariantException(message: String) : IllegalArgumentException(message)

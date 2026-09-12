package com.duluin.ftth.inventory.domain.model

import com.duluin.ftth.common.domain.error.ConflictException
import com.duluin.ftth.common.domain.error.ValidationException
import java.time.Instant
import java.util.UUID

/**
 * Kondisi unit yang ditarik MENURUT TEKNISI di lapangan — bukan vonis akhir.
 *
 * Vonis sebenarnya jatuh di gudang, saat unit berstatus RETURNED diperiksa dan diputuskan jadi
 * AVAILABLE atau QUARANTINE. Yang dijaga enum ini adalah ADANYA pernyataan awal: tanpa itu, ONT
 * yang jelas-jelas hangus dan ONT yang mulus tiba di gudang sebagai dua baris yang identik, dan
 * pemeriksanya tidak punya apa pun untuk diadu selain ingatan teknisi.
 */
enum class RecoveredAssetCondition { GOOD, DAMAGED }

/**
 * Satu unit berserial yang DITARIK dari pelanggan saat work order DISMANTLE.
 *
 * Arahnya kebalikan dari [WorkOrderMaterialLine]: baris material menjawab "apa yang DIAMBIL DARI
 * GUDANG untuk WO ini", baris ini menjawab "apa yang DIBAWA PULANG DARI RUMAH PELANGGAN". Dua
 * pertanyaan yang berbeda, dua tabel yang berbeda — lihat komentar panjang di V197 soal kenapa
 * memaksa keduanya ke satu tabel merusak `plannedQuantity`, `issuedQuantity`, dan penjaga
 * penyelesaian WO sekaligus.
 *
 * TIDAK ADA kuantitas di sini, dan itu disengaja: hanya unit BERSERIAL yang bisa ditarik (D2).
 * Satu baris = satu barang fisik = satu nomor seri.
 *
 * [serialNumber] dan [macAddress] disalin dari asetnya saat ditarik, bukan di-join belakangan:
 * ini catatan historis yang tidak boleh ikut berubah kalau baris asetnya kelak diperbaiki.
 */
data class WorkOrderRecoveredAsset(
    val id: UUID,
    val tenantId: UUID,
    val workOrderId: UUID,
    val assetId: UUID,
    val serialNumber: String,
    val macAddress: String?,
    val itemId: UUID,
    val itemCategory: String,
    val customerId: UUID,
    /**
     * Teknisi yang membawa pulang unitnya, DAN van stock-nya. Keduanya wajib (D6c): saldo yang
     * bertambah saat WO disetujui mendarat persis di dimensi ini. Tanpa keduanya, leg IN jatuh
     * ke dimensi yang tak pernah diisi dan barangnya kembali "ada di sistem tapi tidak di tangan
     * siapa pun" — persis keadaan yang seluruh fitur ini dibuat untuk menutup.
     */
    val technicianId: UUID,
    val technicianLocationId: UUID,
    val condition: RecoveredAssetCondition,
    val note: String?,
    val recoveredAt: Instant,
    val recoveredBy: UUID,
    val cancelledAt: Instant? = null,
    val cancelledBy: UUID? = null,
    val cancelReason: String? = null,
) {
    init {
        require(serialNumber.isNotBlank()) { "nomor seri wajib diisi" }
        require((cancelledAt == null) == (cancelledBy == null)) {
            "pembatalan penarikan wajib menyebut waktu DAN pelakunya"
        }
    }

    /** Baris yang masih hidup — hanya baris inilah yang ikut dipotong saga fulfillment. */
    val active: Boolean get() = cancelledAt == null

    /**
     * Batalkan penarikan ini.
     *
     * Penanda waktu, BUKAN penghapusan baris. Baris yang dihapus tidak bisa menjawab "kenapa
     * saldo tidak bertambah padahal teknisi bilang sudah men-scan"; baris yang dibatalkan bisa,
     * lengkap dengan siapa yang membatalkan dan kenapa.
     *
     * Pembatalan ganda DITOLAK, bukan diam-diam jadi no-op: kalau dibiarkan, alasan pembatalan
     * pertama (yang mungkin satu-satunya yang benar) ditimpa tanpa jejak.
     */
    fun cancel(at: Instant, by: UUID, reason: String?): WorkOrderRecoveredAsset {
        if (!active) throw ConflictException("Penarikan unit $serialNumber sudah dibatalkan sebelumnya")
        return copy(
            cancelledAt = at,
            cancelledBy = by,
            cancelReason = reason?.trim()?.takeIf { it.isNotEmpty() },
        )
    }

    companion object {
        fun parseCondition(raw: String): RecoveredAssetCondition =
            RecoveredAssetCondition.entries.firstOrNull { it.name == raw.trim().uppercase() }
                ?: throw ValidationException("Kondisi unit tarikan harus GOOD atau DAMAGED")
    }
}

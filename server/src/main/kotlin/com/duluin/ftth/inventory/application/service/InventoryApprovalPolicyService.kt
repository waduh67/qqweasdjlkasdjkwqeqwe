package com.duluin.ftth.inventory.application.service

import com.duluin.ftth.common.domain.error.ValidationException
import com.duluin.ftth.inventory.application.port.outbound.InventoryApprovalAuditRepository
import com.duluin.ftth.inventory.application.port.outbound.InventoryApprovalPolicyRepository
import com.duluin.ftth.inventory.domain.model.ApprovalTierRule
import com.duluin.ftth.inventory.domain.model.EmergencyOverrideAudit
import com.duluin.ftth.inventory.domain.model.InventoryApprovalPolicyMatrix
import com.duluin.ftth.inventory.domain.model.InventoryApprovalType
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.MessageDigest
import java.time.Duration
import java.util.UUID

/**
 * Pemilik matriks persetujuan gudang — SATU-SATUNYA tempat tier dan approver ditentukan.
 *
 * Sebelum ini, `InventoryApprovalService.request()` menerima kebijakan lengkap dari pemanggil.
 * Artinya petugas gudang yang mengajukan penghapusbukuan aset boleh menuliskan sendiri
 * "cukup satu tier, ambang nol, approver: rekan saya" — dan basis data akan menyimpannya
 * sebagai snapshot yang terlihat sah. Kontrol yang seharusnya menahan kebocoran aset justru
 * jadi formulir yang diisi sendiri oleh orang yang mau dikontrol.
 */
@Service
class InventoryApprovalPolicyService(
    private val policies: InventoryApprovalPolicyRepository,
    private val audits: InventoryApprovalAuditRepository,
) {
    /**
     * Kebijakan yang BERLAKU untuk satu jenis permintaan: override tenant kalau ada,
     * kalau tidak default bawaan produk.
     */
    @Transactional(readOnly = true)
    fun effectiveFor(tenantId: UUID, type: InventoryApprovalType): InventoryApprovalPolicyMatrix =
        policies.find(tenantId, type) ?: defaultMatrix(tenantId, type)

    @Transactional(readOnly = true)
    fun configured(tenantId: UUID): List<InventoryApprovalPolicyMatrix> = policies.findAll(tenantId)

    /**
     * Seluruh jenis yang butuh persetujuan, lengkap dengan kebijakan yang berlaku —
     * dipakai layar pengaturan supaya operator melihat default yang sedang berlaku,
     * bukan daftar kosong yang membuatnya mengira approval tidak aktif.
     */
    @Transactional(readOnly = true)
    fun effectiveMatrix(tenantId: UUID): List<InventoryApprovalPolicyMatrix> {
        val stored = policies.findAll(tenantId).associateBy { it.type }
        return InventoryApprovalType.entries.map { stored[it] ?: defaultMatrix(tenantId, it) }
    }

    @Transactional
    fun configure(matrix: InventoryApprovalPolicyMatrix): InventoryApprovalPolicyMatrix {
        // Tier tanpa approver ditolak SAAT DISIMPAN, bukan dibiarkan sampai ada yang mengajukan
        // permintaan. Kalau baru meledak di jalur pengajuan, operator gudang yang kena — dan
        // ia tidak punya izin memperbaiki matriksnya sendiri.
        val empty = matrix.tiers.filter { it.approverIds.isEmpty() }
        if (empty.isNotEmpty()) {
            throw ValidationException(
                "Tier " + empty.joinToString { "${it.number} (${it.approverRole})" } + " belum punya approver",
            )
        }
        return policies.save(matrix)
    }

    @Transactional(readOnly = true)
    fun emergencyOverrides(tenantId: UUID): List<EmergencyOverrideAudit> = audits.emergencyOverrides(tenantId)

    /**
     * Default produk: DUA tier — Kepala Gudang untuk setiap permintaan, ditambah Manajer
     * Operasional begitu nilainya melewati ambang. Ambangnya bisa diubah tenant lewat
     * [configure]; jumlah tier default-nya TIDAK, karena satu tier berarti satu orang bisa
     * menyetujui penghapusbukuan aset bernilai berapa pun sendirian.
     *
     * Daftar approver-nya SENGAJA kosong: repo ini belum punya cara menanyakan "siapa saja
     * yang memegang peran X" ke modul `iam` (lihat catatan di README paket kerja), jadi
     * tenant WAJIB menunjuk orangnya secara eksplisit. Yang kosong akan ditolak
     * [InventoryApprovalPolicyMatrix.toPolicy] dengan pesan yang menyebut tier mana —
     * gagal keras dan terbaca, BUKAN diam-diam melewati tier yang tak punya approver.
     */
    private fun defaultMatrix(tenantId: UUID, type: InventoryApprovalType) = InventoryApprovalPolicyMatrix(
        tenantId = tenantId,
        type = type,
        expiry = DEFAULT_EXPIRY,
        emergencyAllowed = false,
        tiers = listOf(
            ApprovalTierRule(1, 0, DEFAULT_TIER_1_ROLE, emptySet()),
            ApprovalTierRule(2, DEFAULT_TIER_2_THRESHOLD, DEFAULT_TIER_2_ROLE, emptySet()),
        ),
        version = 1,
    )

    companion object {
        const val DEFAULT_TIER_1_ROLE = "Kepala Gudang"
        const val DEFAULT_TIER_2_ROLE = "Manajer Operasional"

        /** Rupiah. Ambang bawaan, bisa diubah tenant lewat [configure]. */
        const val DEFAULT_TIER_2_THRESHOLD = 5_000_000L

        val DEFAULT_EXPIRY: Duration = Duration.ofHours(24)

        /**
         * Sidik jari kebijakan yang dibekukan di permintaan.
         *
         * Dihitung SERVER dari matriks yang benar-benar dipakai, tidak lagi diterima dari
         * klien: hash yang dikirim klien tidak membuktikan apa pun — ia cuma menyalin nilai
         * yang ia karang sendiri, sehingga pemeriksaan "apakah kebijakannya berubah?"
         * di kemudian hari selalu bilang "tidak" untuk kebijakan palsu sekalipun.
         */
        fun snapshotHash(matrix: InventoryApprovalPolicyMatrix): String {
            val canonical = buildString {
                append(matrix.type.name).append('|')
                append(matrix.version).append('|')
                append(matrix.expiry.seconds).append('|')
                append(matrix.emergencyAllowed).append('|')
                matrix.tiers.sortedBy { it.number }.forEach { tier ->
                    append(tier.number).append(':')
                    append(tier.minimumAmount).append(':')
                    append(tier.approverRole).append(':')
                    // Diurutkan supaya urutan penyimpanan tidak mengubah hash; kalau tidak,
                    // kebijakan yang sama persis menghasilkan dua sidik jari berbeda dan
                    // audit "kebijakan berubah" penuh dengan alarm palsu.
                    append(tier.approverIds.map(UUID::toString).sorted().joinToString(","))
                    append(';')
                }
            }
            return MessageDigest.getInstance("SHA-256")
                .digest(canonical.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
        }
    }
}

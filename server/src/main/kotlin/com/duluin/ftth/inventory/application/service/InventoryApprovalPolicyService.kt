package com.duluin.ftth.inventory.application.service

import com.duluin.ftth.common.domain.error.ValidationException
import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.iam.IamApi
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
    private val iam: IamApi,
) {
    /**
     * Kebijakan yang BERLAKU untuk satu jenis permintaan: override tenant kalau ada,
     * kalau tidak default bawaan produk — dengan pemegang peran sudah teresolusi.
     *
     * MELEMPAR kalau ada tier yang berakhir tanpa satu pun penyetuju, dan itu bedanya dengan
     * [effectiveMatrix]. Ini jalur PENGAJUAN: membiarkan matriks cacat lewat dari sini berarti
     * permintaan lahir dengan tier yang mustahil dipenuhi — menggantung PENDING sampai
     * kedaluwarsa tanpa seorang pun tahu apa yang salah. Lebih baik pemohonnya ditolak sekarang
     * dengan kalimat yang menyebut peran mana yang kosong.
     */
    @Transactional(readOnly = true)
    fun effectiveFor(tenantId: UUID, type: InventoryApprovalType): InventoryApprovalPolicyMatrix =
        resolve(policies.find(tenantId, type) ?: defaultMatrix(tenantId, type), tenantId)
            .also { requireEveryTierHasApprover(it) }

    @Transactional(readOnly = true)
    fun configured(tenantId: UUID): List<InventoryApprovalPolicyMatrix> = policies.findAll(tenantId)

    /**
     * Seluruh jenis yang butuh persetujuan, lengkap dengan kebijakan yang berlaku —
     * dipakai layar pengaturan supaya operator melihat default yang sedang berlaku,
     * bukan daftar kosong yang membuatnya mengira approval tidak aktif.
     *
     * SENGAJA tidak melempar untuk tier yang kosong. Layar pengaturan justru tempat orang
     * datang untuk MEMPERBAIKI matriks yang kosong; kalau halaman itu sendiri yang meledak, ia
     * tidak punya pintu lain untuk masuk. Tier kosong dikembalikan apa adanya dan UI menandainya
     * lewat `configured`.
     */
    @Transactional(readOnly = true)
    fun effectiveMatrix(tenantId: UUID): List<InventoryApprovalPolicyMatrix> {
        val stored = policies.findAll(tenantId).associateBy { it.type }
        return InventoryApprovalType.entries.map { resolve(stored[it] ?: defaultMatrix(tenantId, it), tenantId) }
    }

    @Transactional
    fun configure(matrix: InventoryApprovalPolicyMatrix): InventoryApprovalPolicyMatrix {
        // Tier tanpa approver ditolak SAAT DISIMPAN, bukan dibiarkan sampai ada yang mengajukan
        // permintaan. Kalau baru meledak di jalur pengajuan, operator gudang yang kena — dan
        // ia tidak punya izin memperbaiki matriksnya sendiri.
        //
        // Yang diperiksa adalah hasil RESOLUSI, bukan daftar nama yang diketik: sejak peran
        // bisa menyumbang penyetuju, menuntut daftar nama terisi akan menolak justru bentuk
        // kebijakan yang paling kita inginkan — "tier 1 disetujui Kepala Gudang, siapa pun
        // yang sedang memegangnya".
        requireEveryTierHasApprover(resolve(matrix, matrix.tenantId))
        // Yang DISIMPAN tetap matriks aslinya. `resolve` hanya dipakai untuk memvalidasi;
        // menyimpan hasilnya akan membekukan pemegang peran hari ini ke dalam jsonb.
        return policies.save(matrix)
    }

    /**
     * Isi [ApprovalTierRule.roleHolderIds] dari modul `iam`.
     *
     * ATURAN yang menjaga seluruh jalur ini tetap aman: resolusi hanya bisa MENAMBAH penyetuju,
     * tidak pernah mengurangi. Maka setiap kegagalannya — peran salah ketik, direktori kosong,
     * konteks tenant tidak ada — bermuara ke tier tanpa penyetuju, yang ditolak keras dan
     * terbaca. Tidak ada satu pun jalan di mana resolusi yang gagal justru membuat permintaan
     * jadi LEBIH mudah disetujui.
     *
     * Pemegang yang nonaktif dibuang, sedangkan [ApprovalTierRule.approverIds] yang ditunjuk
     * manusia TIDAK ikut disaring. Garisnya: apa yang disimpulkan sistem boleh disaring sistem,
     * apa yang diketik manusia tidak. Daftar nama eksplisit memang jalan keluar untuk saat
     * direktori peran tak bisa dipakai — menyaringnya lewat direktori yang sama akan menutup
     * pintu darurat itu tepat ketika ia dibutuhkan.
     */
    private fun resolve(matrix: InventoryApprovalPolicyMatrix, tenantId: UUID): InventoryApprovalPolicyMatrix {
        val active = TenantContext.tenantIdOrNull()
        /*
         * `iam.usersWithRole` menjawab untuk tenant yang AKTIF di context, bukan untuk
         * [tenantId] yang diminta. Selama keduanya sama, tidak ada masalah. Kalau berbeda,
         * yang terjadi adalah satu-satunya arah berbahaya di seluruh method ini: pemegang
         * peran milik tenant lain masuk sebagai penyetuju sah di kebijakan tenant ini —
         * orang luar yang bisa melepas aset, tanpa error di mana pun. Maka DILEMPAR.
         */
        if (active != null && active != tenantId) {
            error("Resolusi peran approval dipanggil untuk tenant $tenantId sementara context aktif $active")
        }
        // Context kosong (worker, seeder) tidak dilempar: ia jatuh ke jalur "hanya daftar nama
        // eksplisit", yang paling buruk hanya menolak permintaan — lihat ATURAN di atas.
        if (active == null) return matrix

        val holders = HashMap<String, Set<UUID>>()
        return matrix.copy(
            tiers = matrix.tiers.map { tier ->
                tier.copy(
                    roleHolderIds = holders.getOrPut(tier.approverRole.trim()) {
                        iam.usersWithRole(tier.approverRole).filter { it.active }.map { it.id }.toSet()
                    },
                )
            },
        )
    }

    /**
     * Tolak matriks yang punya tier tanpa penyetuju, dengan menyebut SEBABNYA.
     *
     * Sebab itu yang membuat pesannya berguna: "peran belum dipegang siapa pun" dituntaskan
     * dengan menunjuk orang di pengaturan pengguna, sedangkan "pemegangnya sudah nonaktif"
     * dituntaskan dengan mengaktifkan kembali atau menunjuk pengganti. Kalau keduanya diratakan
     * jadi "tier kosong", administrator akan membuka layar persetujuan gudang, melihat peran
     * yang terisi rapi di sana, dan menyimpulkan sistemnya yang rusak.
     */
    private fun requireEveryTierHasApprover(matrix: InventoryApprovalPolicyMatrix) {
        val empty = matrix.tiers.filter { it.effectiveApproverIds.isEmpty() }
        if (empty.isEmpty()) return
        val sebab = empty.joinToString("; ") { tier ->
            val pemegang = if (TenantContext.tenantIdOrNull() == null) emptyList() else iam.usersWithRole(tier.approverRole)
            val alasan = when {
                pemegang.isEmpty() -> "peran \"${tier.approverRole}\" belum dipegang siapa pun"
                else -> "seluruh ${pemegang.size} pemegang peran \"${tier.approverRole}\" berstatus nonaktif"
            }
            "tier ${tier.number} — $alasan"
        }
        throw ValidationException(
            "Matriks persetujuan ${matrix.type.name} belum menunjuk approver: $sebab. " +
                "Tunjuk pemegang perannya di pengaturan pengguna, atau sebutkan penyetuju secara " +
                "eksplisit di pengaturan persetujuan gudang.",
        )
    }

    @Transactional(readOnly = true)
    fun emergencyOverrides(tenantId: UUID): List<EmergencyOverrideAudit> = audits.emergencyOverrides(tenantId)

    /**
     * Default produk: DUA tier — Kepala Gudang untuk setiap permintaan, ditambah Manajer
     * Operasional begitu nilainya melewati ambang. Ambangnya bisa diubah tenant lewat
     * [configure]; jumlah tier default-nya TIDAK, karena satu tier berarti satu orang bisa
     * menyetujui penghapusbukuan aset bernilai berapa pun sendirian.
     *
     * Daftar nama eksplisitnya kosong dan memang TIDAK PERLU diisi: sejak [resolve] ada,
     * bawaan ini sudah bisa hidup sendiri di tenant mana pun yang punya pengguna berperan
     * "Kepala Gudang" dan "Manajer Operasional" — tanpa seorang pun mengetik satu UUID.
     * Itulah yang membuat approval gudang menyala secara default alih-alih menunggu
     * administrator menemukan layar pengaturannya.
     *
     * Tenant yang belum punya pemegang peran tersebut tetap ditolak keras dan terbaca oleh
     * [requireEveryTierHasApprover], BUKAN diam-diam melewati tier yang tak punya approver.
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
                    //
                    // Yang disidikjari adalah penyetuju EFEKTIF, termasuk yang datang dari
                    // peran. Konsekuensinya sidik jari ikut berubah saat personel keluar-masuk
                    // peran — dan itu memang yang diinginkan: pertanyaan yang dijawab audit
                    // adalah "apakah himpunan orang yang bisa menyetujui sudah berbeda dari
                    // saat permintaan ini dibuat?", bukan "apakah barisnya sudah diedit?".
                    append(tier.effectiveApproverIds.map(UUID::toString).sorted().joinToString(","))
                    append(';')
                }
            }
            return MessageDigest.getInstance("SHA-256")
                .digest(canonical.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
        }
    }
}

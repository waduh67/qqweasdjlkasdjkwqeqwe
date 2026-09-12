package com.duluin.ftth.inventory.domain.model

import com.duluin.ftth.common.domain.error.ValidationException
import java.time.Duration
import java.util.UUID

/**
 * Satu baris matriks persetujuan: "mulai nilai sekian, peran ini harus ikut menyetujui".
 *
 * [approverRole] menjelaskan MENGAPA seseorang boleh menyetujui — kalimat yang tetap benar
 * saat dibaca auditor dua tahun lagi, ketika orangnya sudah pindah bagian. Siapa orangnya
 * datang dari dua sumber yang SENGAJA dipisah dan tidak pernah dilebur:
 *
 * - [approverIds]: orang yang DITUNJUK NAMANYA oleh administrator. Tersimpan di jsonb.
 * - [roleHolderIds]: pemegang [approverRole] yang aktif, hasil resolusi ke modul `iam`
 *   setiap kali matriks ini dibaca. TIDAK PERNAH ikut tersimpan.
 *
 * Peleburannya jadi satu kolom terlihat menggoda dan justru merusak: begitu pemegang peran
 * ikut tertulis ke jsonb, ia BEKU di sana. Orang yang besok resign tetap tercatat sebagai
 * penyetuju yang sah, dan orang yang besok diangkat jadi Kepala Gudang tidak pernah masuk —
 * persis kebasian yang membuat resolusi lewat peran ini dibangun. Maka yang disimpan hanya
 * yang diketik manusia; sisanya dihitung ulang terus-menerus.
 */
data class ApprovalTierRule(
    val number: Int,
    val minimumAmount: Long,
    val approverRole: String,
    val approverIds: Set<UUID> = emptySet(),
    val roleHolderIds: Set<UUID> = emptySet(),
) {
    /**
     * Gabungan keduanya — inilah yang benar-benar diuji saat seseorang menyetujui.
     *
     * Gabungan, BUKAN "peran kalau ada, kalau tidak baru daftar nama". Bentuk berjenjang
     * membuat penunjukan manual seorang pengganti diam-diam tak berlaku begitu perannya
     * kebetulan punya pemegang, dan administrator yang baru saja menambahkan namanya tidak
     * akan mendapat satu pun pesan yang menjelaskan kenapa ia tetap tak bisa menyetujui.
     */
    val effectiveApproverIds: Set<UUID> get() = approverIds + roleHolderIds
}

/**
 * Kebijakan persetujuan milik SERVER untuk satu jenis permintaan gudang di satu tenant.
 *
 * Ini pengganti [InventoryApprovalPolicy] yang dulu datang dari body request. Bedanya bukan
 * kosmetik: selama kebijakan dikirim klien, siapa pun yang boleh mengajukan restock juga
 * boleh menuliskan "satu tier, ambang 0, approver = saya sendiri" dan menyetujuinya sendiri.
 * Snapshot-nya akan tersimpan rapi di basis data dan terlihat sah — tidak ada satu baris pun
 * yang menunjukkan bahwa kontrol empat-mata baru saja dilangkahi. [InventoryApprovalPolicy]
 * tetap ada sebagai bentuk SNAPSHOT historis; matriks inilah yang menurunkannya.
 */
data class InventoryApprovalPolicyMatrix(
    val tenantId: UUID,
    val type: InventoryApprovalType,
    val expiry: Duration,
    val emergencyAllowed: Boolean,
    val tiers: List<ApprovalTierRule>,
    /**
     * Versi kebijakan untuk jejak audit. Diturunkan dari stempel waktu perubahan terakhir
     * (lihat adapter persistensi), bukan penghitung terpisah: satu kolom penghitung berarti
     * satu lagi hal yang bisa lupa dinaikkan, dan versi yang macet membuat dua kebijakan
     * berbeda tercatat dengan nomor yang sama di riwayat approval.
     */
    val version: Long = 1,
) {
    init {
        if (tiers.isEmpty()) throw ValidationException("Matriks persetujuan wajib punya minimal satu tier")
        if (tiers.map { it.number }.distinct().size != tiers.size) throw ValidationException("Nomor tier tidak boleh kembar")
        // Nomor tier WAJIB berurutan mulai 1. Kalau tier 1 hilang (mis. operator menghapusnya
        // dan menyisakan tier 2), CHECK "tier pertama berambang 0" di basis data tidak lagi
        // menjaga apa pun dan permintaan bernilai kecil kembali jadi permintaan tanpa tier
        // wajib — lahir PENDING, tak bisa disetujui, tak bisa ditolak.
        if (tiers.map { it.number }.sorted() != (1..tiers.size).toList()) {
            throw ValidationException("Nomor tier wajib berurutan mulai dari 1")
        }
        if (tiers.first { it.number == 1 }.minimumAmount != 0L) {
            throw ValidationException("Tier 1 wajib berambang 0 agar setiap permintaan selalu punya minimal satu penyetuju")
        }
        // Ambang harus naik seiring nomor tier: tier yang lebih tinggi berarti wewenang yang
        // lebih besar. Kalau urutannya terbalik, `requiredTiers()` menghasilkan daftar yang
        // tidak sesuai nomornya dan permintaan besar justru melewati atasan.
        val byNumber = tiers.sortedBy { it.number }
        if (byNumber.map { it.minimumAmount } != byNumber.map { it.minimumAmount }.sorted()) {
            throw ValidationException("Ambang nilai wajib naik seiring nomor tier")
        }
        if (tiers.any { it.approverRole.isBlank() }) throw ValidationException("Peran approver wajib diisi")
        if (expiry.seconds !in MIN_EXPIRY_SECONDS..MAX_EXPIRY_SECONDS) {
            throw ValidationException("Masa berlaku persetujuan harus antara 5 menit dan 30 hari")
        }
    }

    /**
     * Turunkan snapshot kebijakan yang akan dibekukan di permintaan.
     *
     * Melempar — bukan diam-diam memakai daftar kosong — kalau ada tier tanpa satu pun
     * approver: [ApprovalTier] menolak himpunan kosong, dan kalau di sini kita "memperbaiki"
     * dengan membuang tier itu, permintaan bernilai besar akan lolos hanya dengan persetujuan
     * kepala gudang dan tidak ada yang menyadarinya sampai barangnya hilang.
     */
    fun toPolicy(): InventoryApprovalPolicy {
        val empty = tiers.filter { it.effectiveApproverIds.isEmpty() }
        if (empty.isNotEmpty()) {
            throw ValidationException(
                "Matriks persetujuan ${type.name} belum menunjuk approver untuk tier " +
                    empty.joinToString { "${it.number} (${it.approverRole})" } +
                    ". Setel dulu lewat pengaturan persetujuan gudang.",
            )
        }
        return InventoryApprovalPolicy(
            version,
            tiers.sortedBy { it.minimumAmount }.map { ApprovalTier(it.number, it.minimumAmount, it.effectiveApproverIds) },
            expiry,
            emergencyAllowed,
        )
    }

    /** Tier yang DILANGKAHI oleh override darurat untuk nilai [amount] — masuk jejak audit. */
    fun bypassedTiers(amount: Long): List<Int> =
        tiers.filter { amount >= it.minimumAmount }.map { it.number }.sorted()

    companion object {
        const val MIN_EXPIRY_SECONDS = 300L
        const val MAX_EXPIRY_SECONDS = 2_592_000L
    }
}

/**
 * Jejak satu override darurat. Immutable di basis data (trigger V179) karena jejak audit yang
 * bisa diperbaiki bukan jejak audit: orang yang memakai override untuk menutupi kebocoran aset
 * tinggal menghapus catatannya sesudahnya.
 */
data class EmergencyOverrideAudit(
    val tenantId: UUID,
    val approvalId: UUID,
    val type: InventoryApprovalType,
    val amount: Long,
    val requesterId: UUID,
    val custodianId: UUID?,
    val reason: String,
    val bypassedTiers: List<Int>,
    val occurredAt: java.time.Instant,
) {
    init {
        require(reason.isNotBlank()) { "alasan override darurat wajib diisi" }
    }
}

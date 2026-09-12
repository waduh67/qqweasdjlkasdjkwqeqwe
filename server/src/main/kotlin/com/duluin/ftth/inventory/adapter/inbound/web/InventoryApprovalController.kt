package com.duluin.ftth.inventory.adapter.inbound.web

import com.duluin.ftth.common.domain.error.NotFoundException
import com.duluin.ftth.common.security.CurrentUserProvider
import com.duluin.ftth.iam.IamApi
import com.duluin.ftth.inventory.application.service.*
import com.duluin.ftth.inventory.domain.model.*
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.PositiveOrZero
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.*
import java.time.Duration
import java.time.Instant
import java.util.UUID

@RestController
@RequestMapping("/api/inventory/approvals")
class InventoryApprovalController(
    private val approvals: InventoryApprovalService,
    private val policies: InventoryApprovalPolicyService,
    private val currentUser: CurrentUserProvider,
    private val iam: IamApi,
) {
    @GetMapping("/pending")
    @PreAuthorize("@authz.can('inventory.approval.view')")
    fun pending(): List<InventoryApprovalRequestView> = withNames(approvals.pendingForCurrentActor())

    @PostMapping
    @PreAuthorize("@authz.can('inventory.approval.request')")
    fun request(@Valid @RequestBody body: ApprovalRequestBody): InventoryApprovalRequestView {
        val actor = currentUser.current()
        return withNames(approvals.request(body.toCommand(actor.tenantId, actor.userId)))
    }

    @PostMapping("/{id}/decision")
    @PreAuthorize("@authz.can('inventory.approval.decide')")
    fun decide(@PathVariable id: UUID, @Valid @RequestBody body: ApprovalDecisionBody): InventoryApprovalRequestView {
        val actor = currentUser.current()
        return withNames(
            approvals.decide(id, DecideInventoryApproval(actor.tenantId, actor.userId, body.decision, body.operationKey, body.operationHash, body.reason)),
        )
    }

    @GetMapping("/{id}")
    @PreAuthorize("@authz.can('inventory.approval.view')")
    fun get(@PathVariable id: UUID): InventoryApprovalRequestView =
        withNames(approvals.get(id) ?: throw NotFoundException("Permintaan persetujuan tidak ditemukan"))

    /**
     * SATU-SATUNYA tempat permintaan persetujuan diterjemahkan jadi bentuk web.
     *
     * Seluruh id — pemohon, penanggung jawab, setiap penyetuju, dan setiap pendelegasi di
     * dalam keputusan — dikumpulkan dulu ke satu himpunan lalu diresolusi SEKALI per
     * permintaan HTTP. Meresolusi per baris akan melahirkan N+1 yang tumbuh persis seiring
     * ramainya antrean: `GET /pending` di gudang yang sibuk memulangkan puluhan permintaan
     * yang masing-masing sudah punya beberapa keputusan, dan layar antrean itulah yang
     * pertama kali melambat justru pada hari yang paling butuh cepat.
     *
     * Himpunan kosong (antrean kosong) SENGAJA tidak memanggil `usersByIds` sama sekali —
     * query `IN ()` untuk nol id adalah perjalanan bolak-balik ke basis data yang hasilnya
     * sudah pasti kosong.
     */
    private fun withNames(requests: List<InventoryApprovalRequest>): List<InventoryApprovalRequestView> {
        val ids = HashSet<UUID>()
        requests.forEach { request ->
            ids += request.requesterId
            request.custodianId?.let { ids += it }
            request.decisions.forEach { decision ->
                ids += decision.approverId
                decision.delegatedFrom?.let { ids += it }
            }
        }
        val names = if (ids.isEmpty()) emptyMap() else iam.usersByIds(ids).associate { it.id to it.name }
        return requests.map { InventoryApprovalRequestView.of(it, names) }
    }

    /**
     * Permintaan tunggal lewat jalur yang PERSIS sama dengan daftar.
     *
     * Bukan sekadar kerapian: kalau pemetaan tunggal punya salinannya sendiri, cepat atau
     * lambat `GET /{id}` dan `GET /pending` akan berbeda bentuk — dan layar detail yang
     * dibuka dari antrean tiba-tiba menampilkan UUID untuk baris yang barusan bernama.
     */
    private fun withNames(request: InventoryApprovalRequest): InventoryApprovalRequestView =
        withNames(listOf(request)).first()

    /**
     * Matriks yang BERLAKU, bukan sekadar yang tersimpan: tipe yang belum pernah dikonfigurasi
     * tetap muncul dengan bawaan dari kode. Kalau hanya baris tersimpan yang ditampilkan,
     * tenant baru melihat halaman kosong dan menyangka tidak ada persetujuan yang berlaku.
     */
    @GetMapping("/policies")
    @PreAuthorize("@authz.can('inventory.approval.view')")
    fun policies(): List<ApprovalPolicyView> {
        val actor = currentUser.current()
        return policies.effectiveMatrix(actor.tenantId).map { ApprovalPolicyView.of(it) }
    }

    @PutMapping("/policies/{type}")
    @PreAuthorize("@authz.can('inventory.approval.manage')")
    fun configurePolicy(@PathVariable type: InventoryApprovalType, @Valid @RequestBody body: ApprovalPolicyBody): ApprovalPolicyView {
        val actor = currentUser.current()
        return ApprovalPolicyView.of(policies.configure(body.toMatrix(actor.tenantId, type)))
    }

    /**
     * Laporan override darurat. Dibuka untuk pemegang `inventory.approval.view` — pengawasan
     * atas kontrol empat-mata yang dilangkahi tidak boleh hanya terlihat oleh orang yang juga
     * berhak melangkahinya.
     */
    @GetMapping("/emergency-overrides")
    @PreAuthorize("@authz.can('inventory.approval.view')")
    fun emergencyOverrides(): List<EmergencyOverrideView> {
        val actor = currentUser.current()
        val entries = policies.emergencyOverrides(actor.tenantId)
        // Satu resolusi untuk seluruh laporan, sama seperti antrean persetujuan: laporan
        // override adalah daftar yang tumbuh terus dan tidak pernah dipangkas.
        val ids = HashSet<UUID>()
        entries.forEach { entry ->
            ids += entry.requesterId
            entry.custodianId?.let { ids += it }
        }
        val names = if (ids.isEmpty()) emptyMap() else iam.usersByIds(ids).associate { it.id to it.name }
        return entries.map { EmergencyOverrideView.of(it, names) }
    }
}

/**
 * Fallback nama: id yang TIDAK teresolusi dikembalikan sebagai UUID-nya sendiri.
 *
 * JANGAN diganti string kosong. Sel kosong di layar terbaca "permintaan ini tidak punya
 * peminta" — padahal yang sebenarnya terjadi adalah pengguna itu sudah dihapus atau
 * dipindahkan tenant, dan itu justru temuan yang harus bisa ditelusuri approver, bukan
 * disembunyikan. UUID yang tercetak masih bisa dicari di basis data; kekosongan tidak.
 */
private fun Map<UUID, String>.nameOf(id: UUID): String = this[id] ?: id.toString()

private fun Map<UUID, String>.nameOrNull(id: UUID?): String? = id?.let { nameOf(it) }

/**
 * Permintaan persetujuan hanya menyebut APA yang diminta.
 *
 * Yang SENGAJA tidak ada lagi di sini: `tiers`, `expiryHours`, dan `policySnapshotHash`.
 * Selama ketiganya datang dari klien, orang yang mengajukan penghapusbukuan aset senilai
 * ratusan juta juga menuliskan sendiri bahwa cukup satu tier dengan approver dirinya sendiri —
 * dan kontrol empat-mata ini tidak menahan apa pun. Sekarang server yang membacanya dari
 * `inventory_approval_policy`.
 */
data class ApprovalRequestBody(
    val type: InventoryApprovalType,
    @field:PositiveOrZero val amount: Long,
    val custodianId: UUID?,
    /** Mutasi PENDING_APPROVAL yang akan diberlakukan begitu permintaan ini disetujui. */
    val movementId: UUID? = null,
    val emergencyReason: String? = null,
    @field:NotBlank val operationKey: String,
    @field:NotBlank val operationHash: String,
) {
    fun toCommand(tenantId: UUID, requesterId: UUID) = CreateInventoryApproval(
        tenantId, type, amount, requesterId, custodianId, operationKey, operationHash, movementId, emergencyReason,
    )
}

data class ApprovalDecisionBody(
    val decision: InventoryApprovalDecision,
    @field:NotBlank val operationKey: String,
    @field:NotBlank val operationHash: String,
    val reason: String? = null,
    /**
     * Diterima demi kompatibilitas klien lama tapi DIABAIKAN: mutasi yang diotorisasi sudah
     * diikat saat permintaan dibuat. Kalau approver masih boleh menentukannya di sini, ia bisa
     * menyetujui permintaan A sambil memberlakukan mutasi B yang tidak pernah dinilai siapa pun.
     */
    @Deprecated("Diabaikan server; mutasi diikat pada permintaan") val movementId: UUID? = null,
)

data class ApprovalPolicyBody(
    @field:NotEmpty val tiers: List<ApprovalTierBody>,
    val expiryHours: Long = 24,
    val emergencyAllowed: Boolean = false,
) {
    fun toMatrix(tenantId: UUID, type: InventoryApprovalType) = InventoryApprovalPolicyMatrix(
        tenantId, type, Duration.ofHours(expiryHours), emergencyAllowed,
        tiers.map { ApprovalTierRule(it.number, it.minimumAmount, it.approverRole, it.approverIds) },
    )
}

data class ApprovalTierBody(
    val number: Int,
    @field:PositiveOrZero val minimumAmount: Long,
    @field:NotBlank val approverRole: String,
    val approverIds: Set<UUID> = emptySet(),
)

data class ApprovalPolicyView(
    val type: InventoryApprovalType,
    val expiryHours: Long,
    val emergencyAllowed: Boolean,
    val configured: Boolean,
    val tiers: List<ApprovalTierView>,
) {
    companion object {
        fun of(matrix: InventoryApprovalPolicyMatrix) = ApprovalPolicyView(
            matrix.type, matrix.expiry.toHours(), matrix.emergencyAllowed,
            // Bendera ini yang memberi tahu UI bahwa permintaan bertipe ini akan DITOLAK sampai
            // ada yang mengisinya. Dihitung dari penyetuju EFEKTIF: tier yang daftar namanya
            // kosong tapi perannya sudah dipegang orang adalah tier yang SIAP PAKAI, dan
            // menandainya "belum dikonfigurasi" akan mengirim administrator mengetik UUID
            // yang tidak ia butuhkan.
            matrix.tiers.all { it.effectiveApproverIds.isNotEmpty() },
            matrix.tiers.map {
                ApprovalTierView(it.number, it.minimumAmount, it.approverRole, it.approverIds, it.roleHolderIds)
            },
        )
    }
}

/**
 * Kedua daftar penyetuju dikirim TERPISAH, bukan sudah tergabung.
 *
 * UI pengaturan perlu membedakannya: [approverIds] boleh dihapus administrator di layar itu,
 * [roleHolderIds] tidak — yang terakhir berubah dengan menugaskan peran di pengaturan
 * pengguna. Kalau keduanya tiba sebagai satu daftar, layar akan menampilkan tombol hapus di
 * sebelah nama yang tak bisa dihapus dari sana, dan administrator akan menyimpulkan
 * penyimpanannya gagal.
 */
data class ApprovalTierView(
    val number: Int,
    val minimumAmount: Long,
    val approverRole: String,
    val approverIds: Set<UUID>,
    val roleHolderIds: Set<UUID>,
)

data class EmergencyOverrideView(
    val approvalId: UUID,
    val type: InventoryApprovalType,
    val amount: Long,
    val requesterId: UUID,
    val requesterName: String,
    val custodianId: UUID?,
    val custodianName: String?,
    val reason: String,
    val bypassedTiers: List<Int>,
    val occurredAt: Instant,
) {
    companion object {
        fun of(entry: EmergencyOverrideAudit, names: Map<UUID, String>) = EmergencyOverrideView(
            entry.approvalId, entry.type, entry.amount,
            entry.requesterId, names.nameOf(entry.requesterId),
            entry.custodianId, names.nameOrNull(entry.custodianId),
            entry.reason, entry.bypassedTiers, entry.occurredAt,
        )
    }
}

/**
 * Permintaan persetujuan dalam bentuk yang SUDAH membawa nama.
 *
 * Alasannya sebuah 403 yang tidak pernah terlihat sebagai 403. Agregat domainnya hanya
 * menyimpan UUID telanjang, dan layar persetujuan gudang dulu meresolusinya sendiri lewat
 * `GET /api/users` — endpoint milik modul iam yang dijaga izin `iam.user.view`. Izin itu
 * LAZIM TIDAK dipegang petugas gudang: yang ia butuhkan cuma `inventory.approval.*`.
 * Akibatnya layar menelan 403-nya diam-diam lalu mencetak UUID di kolom "Pemohon", dan
 * approver diminta menyetujui pelepasan aset tanpa cara apa pun untuk tahu siapa yang
 * memintanya — yang mana persis kebalikan dari gunanya kontrol empat-mata.
 *
 * Resolusinya sekarang terjadi di sisi server lewat [IamApi], yang IN-PROCESS sehingga
 * tidak melewati pemeriksaan izin web `iam.user.view` sama sekali. Nama yang ikut adalah
 * nama yang memang dibutuhkan untuk membaca permintaan ini; ia tidak membuka direktori
 * pengguna bagi siapa pun.
 *
 * [InventoryApprovalRequest] dan [InventoryApprovalDecisionSnapshot] SENGAJA tidak ikut
 * diubah. Keduanya tipe kontrak [com.duluin.ftth.inventory.InventoryApprovalApi] lintas
 * modul sekaligus bentuk yang dibaca-tulis repository; menyelipkan nama ke dalamnya berarti
 * setiap pemanggil non-web dan setiap pembacaan dari basis data harus ikut mengarang nama
 * yang tidak ia punya — dan nama yang ikut tersimpan akan MEMBEKU, menampilkan nama lama
 * seseorang selamanya setelah ia ganti nama. Nama adalah urusan tampilan, dan hidupnya
 * berakhir di lapisan web ini.
 */
data class InventoryApprovalRequestView(
    val approvalId: UUID,
    val tenantId: UUID,
    val type: InventoryApprovalType,
    val amount: Long,
    val requesterId: UUID,
    val requesterName: String,
    val custodianId: UUID?,
    /** Null HANYA kalau [custodianId] null; id yang tak teresolusi jadi UUID-nya, bukan kosong. */
    val custodianName: String?,
    val movementId: UUID?,
    val policy: InventoryApprovalPolicy,
    val policySnapshotHash: String,
    val operationKey: String,
    val operationHash: String,
    val emergencyReason: String?,
    val requestedAt: Instant,
    val expiresAt: Instant,
    val status: InventoryApprovalStatus,
    val revision: Long,
    val decisions: List<InventoryApprovalDecisionView>,
) {
    companion object {
        fun of(request: InventoryApprovalRequest, names: Map<UUID, String>) = InventoryApprovalRequestView(
            request.approvalId, request.tenantId, request.type, request.amount,
            request.requesterId, names.nameOf(request.requesterId),
            request.custodianId, names.nameOrNull(request.custodianId),
            request.movementId,
            // `policy` diteruskan APA ADANYA: daftar penyetuju di dalamnya adalah snapshot
            // audit, bukan kolom yang dibaca manusia di layar antrean.
            request.policy,
            request.policySnapshotHash, request.operationKey, request.operationHash,
            request.emergencyReason, request.requestedAt, request.expiresAt,
            request.status, request.revision,
            request.decisions.map { InventoryApprovalDecisionView.of(it, names) },
        )
    }
}

/**
 * Satu keputusan, dengan nama penyetujunya ikut.
 *
 * [delegatedFromName] penting terpisah dari [approverName]: riwayat yang hanya menyebut satu
 * nama tidak bisa membedakan "Budi menyetujui" dari "Budi menyetujui atas delegasi Sari" —
 * dan pertanyaan yang diajukan audit setahun kemudian justru yang kedua.
 */
data class InventoryApprovalDecisionView(
    val decisionId: UUID,
    val tier: Int,
    val approverId: UUID,
    val approverName: String,
    val delegatedFrom: UUID?,
    /** Null HANYA kalau [delegatedFrom] null. */
    val delegatedFromName: String?,
    val decision: InventoryApprovalDecision,
    val reason: String?,
    val decidedAt: Instant,
    val revision: Long,
    val operationKey: String,
    val operationHash: String,
) {
    companion object {
        fun of(snapshot: InventoryApprovalDecisionSnapshot, names: Map<UUID, String>) = InventoryApprovalDecisionView(
            snapshot.decisionId, snapshot.tier,
            snapshot.approverId, names.nameOf(snapshot.approverId),
            snapshot.delegatedFrom, names.nameOrNull(snapshot.delegatedFrom),
            snapshot.decision, snapshot.reason, snapshot.decidedAt, snapshot.revision,
            snapshot.operationKey, snapshot.operationHash,
        )
    }
}

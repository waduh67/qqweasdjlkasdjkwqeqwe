package com.duluin.ftth.onboarding.application.port.inbound

import com.duluin.ftth.common.domain.geo.Coordinate
import java.util.UUID

/**
 * Bulk-import PPPoE: migrasi akun `/ppp/secret` sebuah RouterOS menjadi pelanggan +
 * langganan + a pending fulfillment request — untuk operator
 * yang memindah pelanggan existing dari router lama ke platform. Berbeda dari PSB ekspres:
 * TAK ada Work Order (pelanggan sudah terpasang di lapangan) dan langganan langsung diaktifkan
 * lalu diprovisi ke RADIUS (bukan menunggu WO PSB).
 *
 * Sumber baris ([ImportSource]):
 *  - [ImportSource.NAS]    server menarik `/ppp/secret` langsung dari RouterOS BRAS (password
 *                          tak pernah lewat browser).
 *  - [ImportSource.INLINE] operator menempel/upload hasil export; baris (termasuk password)
 *                          datang di [ImportPppoeCommand.rows].
 *
 * Per-baris ATOMIK (bukan satu transaksi raksasa): tiap baris commit sendiri, jadi satu baris
 * gagal tak menggagalkan seluruh batch. Hasilnya per-baris ([ImportRowOutcome]).
 *
 * Catatan penting yang diwariskan ke pemanggil/operator:
 *  - `/ppp/secret` tak punya alamat/koordinat pelanggan → import memakai placeholder
 *    ([ImportPppoeCommand.defaultAddress]/[ImportPppoeCommand.defaultLocation]); operator
 *    memperkaya lokasi/alamat asli belakangan.
 *  - ALREADY_INSTALLED queues fulfillment; activation and provisioning are coordinator effects.
 */
interface ImportPppoeUseCase {

    fun importPppoe(command: ImportPppoeCommand): ImportPppoeResult
}

/** Dari mana baris impor datang. */
enum class ImportSource { NAS, INLINE }

/**
 * Perintah bulk-import PPPoE. [nasId] = BRAS tujuan (sekaligus sumber bila [source] NAS).
 * [rows] wajib untuk [ImportSource.INLINE], diabaikan untuk NAS (server menarik sendiri).
 * [profilePlanId] memetakan profil RouterOS → paket katalog; [defaultPlanId] fallback bila
 * profil tak terpetakan (null → baris ber-profil tak dikenal dilewati). [onlyNames] null =
 * semua baris; berisi = hanya username terpilih operator. [skipDisabled] melewati akun yang
 * dimatikan di router. [areaId]/[defaultAddress]/[defaultLocation] mengisi data pelanggan yang
 * tak ada di `/ppp/secret` (placeholder, diperkaya belakangan).
 */
data class ImportPppoeCommand(
    val nasId: UUID,
    val source: ImportSource,
    val rows: List<ImportRow>,
    val profilePlanId: Map<String, UUID>,
    val defaultPlanId: UUID?,
    val skipDisabled: Boolean,
    val onlyNames: Set<String>?,
    val areaId: UUID?,
    val defaultAddress: String?,
    val defaultLocation: Coordinate?,
    val schemaVersion: Int = 1,
    val mode: ImportMode = ImportMode.ALREADY_INSTALLED,
    val operationKey: String? = null,
)

/**
 * Satu baris impor (untuk sumber INLINE, atau bentuk internal setelah server menarik dari NAS).
 * [name] = username PPPoE (juga jadi kode pelanggan), [password] plaintext dari router (dipakai
 * apa adanya agar login tetap jalan; null → server generate password baru), [profile] dipetakan
 * ke paket, [comment] jadi nama pelanggan bila terisi, [disabled] menandai akun mati di router.
 */
data class ImportRow(
    val name: String,
    val password: String?,
    val profile: String?,
    val comment: String?,
    val disabled: Boolean,
)

/** Rekap hasil impor + rincian per-baris. */
data class ImportPppoeResult(
    val created: Int,
    val skipped: Int,
    val failed: Int,
    val rows: List<ImportRowOutcome>,
)

/** Nasib satu baris: dibuat, dilewati (mis. sudah ada / profil tak dipetakan), atau gagal. */
data class ImportRowOutcome(
    val username: String,
    val status: ImportRowStatus,
    val message: String?,
)

enum class ImportRowStatus { CREATED, SKIPPED, FAILED }

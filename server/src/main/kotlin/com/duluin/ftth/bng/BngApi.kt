package com.duluin.ftth.bng

import java.time.Instant
import java.util.UUID

/**
 * Kontrak publik module bng untuk module lain (gis, saat memperkaya telusur jalur
 * dengan hop BRAS: identitas jaringan pelanggan + keadaan sesi PPPoE terkininya).
 *
 * Berbeda dari [com.duluin.ftth.bng.application.port.inbound.ViewBngSessionUseCase]
 * yang melayani UI module bng sendiri (per-akun), kontrak ini menjawab per-PELANGGAN
 * dan hanya membocorkan yang perlu lintas-module — tak pernah rahasia (password PPPoE
 * / secret CoA).
 */
interface BngApi {

    /**
     * Identitas jaringan + sesi PPPoE terkini seorang pelanggan. Bila pelanggan punya
     * beberapa akun, yang sesinya online didahulukan lalu yang pertama. `null` bila
     * pelanggan belum punya akun jaringan sama sekali (belum diprovisi PPPoE).
     */
    fun findSubscriberSession(customerId: UUID): SubscriberSessionRef?

    /**
     * Onboarding: provisikan identitas jaringan (akun PPPoE) untuk sebuah langganan lewat
     * kontrak publik. Aturan module bng tetap ditegakkan (paket melayani tipe, satu langganan
     * satu akun, kredensial di-generate bila dikosongkan). Bila langganan masih PENDING akun
     * lahir PENDING dan BELUM ditulis ke RADIUS (baru saat WO PSB selesai). Mengembalikan id +
     * username final (server bisa meng-generate username); TAK pernah mengembalikan secret.
     */
    fun provisionAccess(command: ProvisionAccessSpec): ProvisionedAccessRef

    /**
     * BRAS yang menaungi sebuah area (cakupan area→BRAS), atau `null` bila area itu belum
     * dipetakan ke BRAS mana pun. Dipakai onboarding PSB untuk memilih BRAS otomatis dari
     * area pelanggan bila operator tak memilih BRAS manual. Deterministik: tiap area dinaungi
     * paling banyak satu BRAS. Ter-scope tenant aktif (RLS).
     */
    fun resolveNasForArea(areaId: UUID): UUID?

    /**
     * Resolusi BRAS menurut NAMA (abai huruf besar/kecil) — impor CSV pelanggan merujuk BRAS
     * lewat kolom `router_name`, bukan UUID. `null` bila nama tak cocok BRAS mana pun. Ter-scope
     * tenant aktif (RLS).
     */
    fun resolveNasByName(name: String): UUID?

    /**
     * Cari akun jaringan menurut username (kunci upsert impor CSV pelanggan: `mikrotik_username`).
     * `null` bila belum ada akun dengan username itu → pemanggil membuat pelanggan+langganan+akun baru.
     * Membawa `subscriptionId`/`customerId` agar orkestrasi impor bisa memperbarui langganan &
     * biodata pemiliknya tanpa menembus internal module. TAK membocorkan secret.
     */
    fun findAccessByUsername(username: String): ImportedAccessRef?

    /**
     * Perbarui akun jaringan yang sudah ada dari impor CSV (jalur upsert): pindahkan paket/BRAS
     * dan—bila [secret] diisi—ganti password. [secret] null = pertahankan password lama (kolom
     * kosong di CSV dilewati). Untuk akun berbasis MAC, [secret] diabaikan. Memakai kembali use
     * case pengelolaan akun (audit & sinkron RADIUS ikut berjalan).
     */
    fun updateAccessFromImport(accessId: UUID, planId: UUID, nasId: UUID?, secret: String?)

    /**
     * Tarik seluruh `/ppp/secret` dari RouterOS sebuah BRAS (vendor MIKROTIK) — bahan mentah
     * bulk-import PPPoE. BERBEDA dari pratinjau UI ([ManageNasUseCase.listPppSecrets]), ini
     * MEMBAWA password: dipakai orkestrasi impor onboarding untuk membuat akun dengan
     * password asli router agar pelanggan tetap bisa login setelah pindah ke RADIUS pusat.
     * Karena itu jalur ini INTERNAL antar-module dan TAK pernah di-serialisasi utuh ke
     * browser oleh bng. Menyentuh router langsung; gagal 409 bila tak terjangkau. RLS-scoped.
     */
    fun fetchPppSecretsFromNas(nasId: UUID): List<PppSecretRef>

    /**
     * Keadaan hidup PPPoE seluruh akun ACTIVE tenant — bahan monitoring menilai alarm
     * `PPPOE_DOWN` (sesi putus → pelanggan offline walau ONU boleh jadi masih menyala).
     *
     * [SubscriberPppoeLiveness.online] SUDAH memperhitungkan ambang basi: poll BRAS hanya
     * melaporkan sesi yang hidup, sesi yang berakhir MENGHILANG dari `radacct` tanpa pernah
     * ditandai offline — jadi baris ber-`online=true` yang tak diperbarui melebihi ambang
     * dianggap putus. Satu pelanggan bisa punya beberapa akun (unit kedua): masing-masing
     * jadi satu baris; agregasi per-pelanggan diserahkan ke pemanggil. RLS-scoped.
     */
    fun activeSubscriberLiveness(): List<SubscriberPppoeLiveness>

    /**
     * Seluruh akun jaringan tenant untuk EKSPOR CSV pelanggan (simetris dengan impor). Anchor =
     * `username` (kunci upsert). Membawa taut ke langganan & pemiliknya plus nama BRAS ter-resolusi,
     * TANPA secret — password PPPoE tak pernah diekspor (impor ulang memperlakukan kolom kosong =
     * "pertahankan"). Terurut menurut username agar keluaran CSV deterministik. RLS-scoped.
     */
    fun exportAccesses(): List<AccessExportRef>
}

/**
 * Identitas ringkas satu akun jaringan untuk EKSPOR CSV pelanggan. [authType] nama tipe layanan
 * (mis. "PPPOE") yang dipetakan ke kolom `connection_type`; [nasName] nama BRAS ter-resolusi untuk
 * kolom `router_name` (null bila akun tak ber-BRAS). TANPA secret.
 */
data class AccessExportRef(
    val username: String,
    val authType: String,
    val subscriptionId: UUID,
    val customerId: UUID,
    val nasName: String?,
)

/**
 * Keadaan hidup satu akun PPPoE ACTIVE untuk penilaian alarm lintas-module. Tanpa rahasia
 * apa pun. [online] adalah putusan akhir (sudah memperhitungkan ambang basi sesi), bukan
 * sekadar flag mentah baris `radacct`. [customerId] didenormalisasi agar monitoring bisa
 * mewarnai peta lewat customerId tanpa menembus module customer.
 */
data class SubscriberPppoeLiveness(
    val customerId: UUID,
    val username: String,
    val online: Boolean,
    /** Kapan BRAS terakhir melaporkan sesi ini; `null` bila belum pernah terpantau. */
    val lastSeenAt: Instant?,
)

/**
 * Satu baris `/ppp/secret` RouterOS untuk orkestrasi impor lintas-module. [password] adalah
 * plaintext dari router — sengaja dibawa agar akun impor pakai password asli; jangan
 * bocorkan ke luar batas onboarding. [profile] dipetakan operator ke paket katalog.
 */
data class PppSecretRef(
    val name: String,
    val password: String?,
    val profile: String?,
    val service: String?,
    val comment: String?,
    val disabled: Boolean,
)

/**
 * Perintah provisi akun jaringan lintas-module (orkestrasi onboarding). [username]/[secret]
 * null/kosong → di-generate server-side untuk PPPoE/Hotspot; untuk DHCP/Static [username]
 * adalah MAC (wajib) dan [secret] diabaikan. [authType] null/kosong → PPPOE.
 */
data class ProvisionAccessSpec(
    val subscriptionId: UUID,
    val username: String?,
    val secret: String?,
    val planId: UUID,
    val nasId: UUID?,
    val authType: String?,
    val framedIp: String?,
)

/**
 * Identitas ringkas sebuah akun jaringan untuk jalur upsert impor CSV — hasil pencarian
 * per-username. Membawa taut ke langganan & pemiliknya plus paket/BRAS terkini (untuk merge
 * partial-update), TANPA secret. [macBased] menandai akun DHCP/Static yang passwordnya = MAC
 * (tak bisa di-reset dari impor).
 */
data class ImportedAccessRef(
    val accessId: UUID,
    val subscriptionId: UUID,
    val customerId: UUID,
    val planId: UUID,
    val nasId: UUID?,
    val macBased: Boolean,
)

/** Hasil provisi: identitas akun tanpa membocorkan secret (password PPPoE tak pernah keluar). */
data class ProvisionedAccessRef(
    val accessId: UUID,
    val username: String,
    /** Status akun: PENDING/ACTIVE/ISOLATED/TERMINATED. */
    val status: String,
)

/**
 * Pandangan lintas-module identitas jaringan + sesi terkini seorang pelanggan. Tanpa
 * rahasia apa pun (password PPPoE / secret CoA tak pernah keluar). Waktu semuanya UTC;
 * UI yang menyesuaikan zona.
 */
data class SubscriberSessionRef(
    val subscriberAccessId: UUID,
    val username: String,
    /** Status akun jaringan: ACTIVE/ISOLATED/TERMINATED. */
    val accessStatus: String,
    /**
     * Nama paket (katalog) yang menempel pada akun; `null` bila paket telah dinonaktifkan/
     * terhapus. Nama field dipertahankan (bukan `planName`) demi kestabilan konsumen gis/web.
     */
    val rateProfileName: String?,
    val online: Boolean,
    val framedIp: String?,
    val nasId: UUID?,
    val nasName: String?,
    val nasIp: String?,
    val uptimeSeconds: Long?,
    val startedAt: Instant?,
    /** Kapan terakhir BRAS melaporkan akun ini; `null` bila belum pernah terpantau. */
    val lastSeenAt: Instant?,
)

package com.duluin.ftth.bng.application.port.outbound

import com.duluin.ftth.bng.domain.model.AccountingRecordPoint
import com.duluin.ftth.bng.domain.model.BngAction
import com.duluin.ftth.bng.domain.model.Nas
import com.duluin.ftth.bng.domain.model.RadiusSession
import com.duluin.ftth.bng.domain.model.SubscriberAccess
import com.duluin.ftth.bng.domain.model.TrafficSample
import java.time.Instant
import java.util.UUID

/**
 * Port persistence module bng. Semua tabel tenant-aware (@TenantId + RLS), jadi
 * semua pencarian ter-scope tenant aktif secara otomatis — tak ada parameter
 * tenantId yang dibawa-bawa.
 */
interface NasRepository {

    fun save(nas: Nas): Nas

    fun findById(id: UUID): Nas?

    fun findAll(): List<Nas>

    fun existsByName(name: String): Boolean

    /** Cari BRAS menurut nama (abai huruf besar/kecil) — resolusi impor CSV kolom `router_name`. `null` bila tak ada. */
    fun findByNameIgnoreCase(name: String): Nas?

    fun deleteById(id: UUID)
}

/**
 * Cakupan area per-BRAS — dasar auto-pilih BRAS dari area pelanggan saat PSB. Tenant-aware
 * (RLS), jadi semua pencarian ter-scope tenant aktif otomatis. Tiap area dinaungi PALING
 * BANYAK satu BRAS (unik per tenant di migrasi), jadi [findNasIdByAreaId] mengembalikan 0..1
 * dan resolusinya deterministik. [areaId] merujuk area milik iam sebagai UUID polos (tanpa
 * FK lintas-module) — batas module dijaga di kode.
 */
interface NasAreaCoverageRepository {

    /** Area yang dinaungi sebuah BRAS. */
    fun findAreaIdsByNasId(nasId: UUID): List<UUID>

    /** Peta nasId → area-nya untuk sekumpulan BRAS (hindari N+1 saat menyusun daftar). */
    fun findAreaIdsByNasIds(nasIds: Collection<UUID>): Map<UUID, List<UUID>>

    /** BRAS yang menaungi sebuah area; `null` bila area belum dipetakan ke BRAS mana pun. */
    fun findNasIdByAreaId(areaId: UUID): UUID?

    /** Ganti TOTAL cakupan sebuah BRAS: lepas yang lama, pasang tepat [areaIds] baru. */
    fun replaceCoverage(nasId: UUID, areaIds: Collection<UUID>)

    /** Lepas seluruh cakupan sebuah BRAS (dipanggil saat BRAS dihapus). */
    fun deleteByNasId(nasId: UUID)
}

interface SubscriberAccessRepository {

    fun save(access: SubscriberAccess): SubscriberAccess

    fun findById(id: UUID): SubscriberAccess?

    /** Semua akun tenant aktif, terurut username — dipakai ekspor CSV pelanggan (anchor username). */
    fun findAll(): List<SubscriberAccess>

    fun findByCustomerId(customerId: UUID): List<SubscriberAccess>

    /**
     * Versi batch [findByCustomerId] — akun seluruh pelanggan dalam himpunan, satu query.
     * Dipakai jalur lintas-module yang menyusun tabel armada (konsol ACS): tanpa ini tiap
     * baris memicu satu query. Terurut username agar pemilihan akun deterministik.
     */
    fun findByCustomerIds(customerIds: Collection<UUID>): List<SubscriberAccess>

    fun findBySubscriptionId(subscriptionId: UUID): List<SubscriberAccess>

    fun findByUsername(username: String): SubscriberAccess?

    /** Semua akun yang dinaungi sebuah BRAS — dipakai jalur baca untuk tahu siapa yang diharapkan online. */
    fun findByNasId(nasId: UUID): List<SubscriberAccess>

    /** Semua akun yang memakai sebuah paket — dipakai re-sync grup RADIUS saat paket berubah. */
    fun findByPlanId(planId: UUID): List<SubscriberAccess>

    /**
     * Akun ACTIVE yang sudah ditugaskan ke BRAS — kandidat penegakan FUP. Hanya akun
     * aktif & ber-BRAS yang relevan: terisolir/terhenti sudah terputus, tak-ber-BRAS tak
     * punya sesi untuk di-throttle.
     */
    fun findActiveOnNas(): List<SubscriberAccess>

    /**
     * Username (MAC) akun ACTIVE berbasis MAC (DHCP/Static) — dipakai jalur-baca `radacct`
     * untuk menyaring balik baris mereka yang ditulis POLOS tanpa prefiks tenant. Hanya
     * username yang dikembalikan (bukan agregat penuh) sebab pemanggil hanya butuh itu.
     */
    fun findActiveMacUsernames(): List<String>

    fun existsBySubscriptionId(subscriptionId: UUID): Boolean

    /** Berapa akun yang masih dinaungi sebuah BRAS — untuk mencegah hapus BRAS terpakai. */
    fun countByNasId(nasId: UUID): Long

    fun deleteById(id: UUID)
}

/**
 * Sesi PPPoE terkini per akun — di-upsert tiap poll BRAS. Bukan deret waktu:
 * satu baris per akun, hanya keadaan terakhir. Tenant-aware (RLS), jadi pencarian
 * ter-scope tenant aktif otomatis.
 */
interface RadiusSessionRepository {

    fun save(session: RadiusSession): RadiusSession

    fun findBySubscriberAccessId(subscriberAccessId: UUID): RadiusSession?

    /**
     * Versi batch [findBySubscriberAccessId], sudah dipetakan `accessId → sesi`. Akun yang
     * belum pernah terpantau tak muncul di peta (dianggap "belum diketahui", bukan "putus").
     */
    fun findBySubscriberAccessIds(subscriberAccessIds: Collection<UUID>): Map<UUID, RadiusSession>

    /**
     * Sesi terkini seluruh akun ACTIVE — dasar penilaian alarm PPPoE putus oleh monitoring.
     * Hanya akun ACTIVE yang relevan: PENDING belum ditulis ke RADIUS, ISOLATED/TERMINATED
     * memang tak diharapkan online. Akun ACTIVE yang belum pernah terpantau (tanpa baris
     * sesi) tak muncul di sini — dianggap "belum diketahui", bukan "putus".
     */
    fun findAllForActiveAccounts(): List<RadiusSession>

    /**
     * Seluruh sesi yang masih tercatat online — bahan sapuan "sesi yang menghilang dari
     * `radacct` berarti berakhir". Tak disaring status akun: akun yang baru diisolir atau
     * diberhentikan justru yang paling perlu ketahuan sudah benar-benar turun.
     */
    fun findOnline(): List<RadiusSession>
}

/**
 * Deret waktu akunting (hypertable Timescale). Penulisan lewat JDBC batch agar GUC
 * `app.tenant_id` ikut (RLS), pembacaan menghitung laju di SQL dari selisih penghitung
 * kumulatif antar cuplikan berurutan.
 */
interface AccountingRecordRepository {

    /** Menyimpan cuplikan; duplikat pada (akun, waktu) diabaikan diam-diam. */
    fun saveAll(points: List<AccountingRecordPoint>)

    /**
     * Tren trafik satu akun sejak [since], sudah dihitung jadi Mbps per titik. Titik pertama
     * tiap rentang tak punya laju (belum ada pembanding) → null.
     *
     * Cuplikan mentah lebih dulu diringkas ke ember [bucketSeconds] detik (satu wakil per ember)
     * agar jumlah titik tetap terkendali pada rentang panjang — mentahnya bisa puluhan ribu.
     * Laju dihitung antar-ember; makin lebar ember makin halus (rata-rata makin lebar).
     */
    fun trafficSince(subscriberAccessId: UUID, since: Instant, bucketSeconds: Long): List<TrafficSample>

    /**
     * Total octet (unggah+unduh) terpakai tiap akun sejak [since] — dasar penegakan FUP.
     * Sadar-reset: penghitung kumulatif yang MUNDUR (sesi baru menyetel ulang counter)
     * dihitung dari nol, bukan jadi selisih negatif; titik pertama tiap akun jadi baseline
     * (kontribusi 0) agar byte sebelum [since] tak ikut terhitung. Akun tanpa cuplikan pada
     * rentang tak muncul di peta (dianggap 0 oleh pemanggil).
     */
    fun usageSince(subscriberAccessIds: Collection<UUID>, since: Instant): Map<UUID, Long>
}

/**
 * Antrean sekaligus jejak audit perintah BRAS (jalur turun, Phase 7c). Tenant-aware
 * (RLS), jadi pencarian ter-scope tenant aktif otomatis.
 */
interface BngActionRepository {

    fun save(action: BngAction): BngAction

    fun findById(id: UUID): BngAction?

    /**
     * Perintah KONTROL SESI ([BngActionType.SESSION_CONTROL]: DISCONNECT/COA) yang belum
     * tuntas (PENDING atau DISPATCHED) untuk sekumpulan BRAS — dasar dispatch ke collector.
     * Aksi jalur-data (PROVISION/DEPROVISION/SYNC_GROUP) SENGAJA tak ikut: sejak
     * RADIUS-as-a-service, server yang mengeksekusinya langsung ke radius-db (lihat
     * [findServerProvisioningPending]) — collector on-prem tak punya rute ke radius-db
     * internal. DISPATCHED ikut agar perintah yang belum di-ACK dikirim ulang
     * (at-least-once). Terurut waktu minta agar urutannya stabil.
     */
    fun findDispatchableByNasIds(nasIds: Collection<UUID>): List<BngAction>

    /**
     * Aksi jalur-DATA RADIUS ([BngActionType.PROVISIONING]) yang masih PENDING untuk tenant
     * aktif (RLS) — diklaim worker server-side untuk ditulis ke radius-db platform. Tak
     * pakai status DISPATCHED (worker mengeksekusi sinkron lalu menuntaskan sendiri).
     * Dibatasi [limit] agar satu tenant tak memonopoli satu putaran; terurut waktu minta.
     */
    fun findServerProvisioningPending(limit: Int): List<BngAction>

    /**
     * Perintah KONTROL SESI ([BngActionType.SESSION_CONTROL]: DISCONNECT/COA) yang masih
     * PENDING untuk sekumpulan BRAS yang server jangkau sendiri (reachability ≠ COLLECTOR)
     * — diklaim worker DAE server-side (RADIUS-as-a-service). Cermin
     * [findServerProvisioningPending] tapi disaring per-BRAS: BRAS COLLECTOR tetap dilayani
     * agent on-prem lewat [findDispatchableByNasIds]. Tanpa status DISPATCHED (server
     * mengeksekusi sinkron lalu menuntaskan sendiri). Dibatasi [limit], terurut waktu minta.
     */
    fun findServerSessionControlPending(nasIds: Collection<UUID>, limit: Int): List<BngAction>

    /**
     * Dari [subscriberAccessIds], mana yang masih punya aksi jalur-DATA
     * ([BngActionType.PROVISIONING]) berstatus PENDING — yakni akun yang otorisasi barunya
     * belum sampai ke radius-db.
     *
     * Dipakai worker kontrol sesi sebagai penjaga urutan. Provisioning dan kontrol sesi
     * dikerjakan dua worker berbeda yang berlomba pada selang yang sama, jadi tanpa penjaga
     * ini DISCONNECT bisa mendahului PROVISION yang mengiringinya: pelanggan diputus, dial
     * ulang dalam hitungan detik, dan disambut grup LAMA — persis kebalikan dari yang diminta.
     * Paling terasa pada isolir (pelanggan kembali dengan kecepatan penuh) dan pada FUP.
     */
    fun findAccessIdsWithPendingProvisioning(subscriberAccessIds: Collection<UUID>): Set<UUID>
}

package com.duluin.ftth.contract

import java.time.Instant

/**
 * Protokol collector ↔ server.
 *
 * Arah koneksinya selalu dari collector KE server (outbound). ISP tidak perlu
 * membuka port atau mengatur port-forwarding, dan server tidak pernah perlu tahu
 * alamat jaringan pelanggan — ini yang membuat pemasangannya sesederhana
 * menjalankan satu proses di balik NAT.
 *
 * Setiap perubahan yang tidak kompatibel harus menaikkan [PROTOCOL_VERSION];
 * server menolak collector dengan versi mayor berbeda agar agent lama tidak
 * diam-diam mengirim data yang salah tafsir.
 */
object CollectorProtocol {
    const val PROTOCOL_VERSION = 1

    /** Header berisi API key collector. Kunci mentah tidak pernah disimpan server. */
    const val API_KEY_HEADER = "X-Collector-Key"

    const val PROTOCOL_VERSION_HEADER = "X-Collector-Protocol"
}

// ---------------------------------------------------------------------------
// Collector → server
// ---------------------------------------------------------------------------

/**
 * Denyut nadi sekaligus permintaan konfigurasi.
 *
 * Konfigurasi polling dikirim balik server sebagai jawaban, bukan disimpan di
 * berkas lokal collector: operator mengatur OLT dan interval dari UI, dan
 * collector menyesuaikan diri pada denyut berikutnya tanpa perlu di-deploy ulang.
 */
data class CollectorHeartbeat(
    val agentVersion: String,
    val protocolVersion: Int = CollectorProtocol.PROTOCOL_VERSION,
    /** Ringkasan hasil siklus polling terakhir, untuk ditampilkan di UI. */
    val lastCycle: CycleReport? = null,
    /**
     * Hasil eksekusi perintah BRAS (Reset Login/isolir/CoA) yang dikirim server pada
     * denyut-denyut sebelumnya — jalur ACK (Phase 7c). Aditif dan kosong bila collector
     * tidak menjalankan perintah apa pun; agent lama tak mengisinya. Server memakainya
     * untuk menuntaskan status perintah di antrean.
     */
    val actionResults: List<BngActionResult> = emptyList(),
)

data class CycleReport(
    val startedAt: Instant,
    val finishedAt: Instant,
    val targetsPolled: Int,
    val targetsFailed: Int,
    val readingsCollected: Int,
    /** Galat per OLT, kosong bila semua mulus. */
    val failures: List<TargetFailure> = emptyList(),
)

data class TargetFailure(
    val oltId: String,
    val oltCode: String,
    val message: String,
)

/**
 * Kiriman batch hasil polling.
 *
 * [batchId] dibuat collector dan diulang bila pengiriman gagal, sehingga server
 * bisa membuang kiriman ganda. Tanpa ini, jaringan ISP yang putus-nyambung akan
 * menghasilkan metrik dobel yang merusak agregasi.
 */
data class MetricBatch(
    val batchId: String,
    val collectedAt: Instant,
    val readings: List<OnuReading>,
) {
    companion object {
        /** Batas jumlah bacaan per kiriman, agar satu request tidak membengkak. */
        const val MAX_READINGS = 5_000
    }
}

/**
 * Satu bacaan dari sebuah ONU.
 *
 * ONU dikenali lewat [serialNumber], BUKAN id internal server: collector membaca
 * apa yang dilaporkan OLT dan tidak tahu-menahu soal basis data server. Server
 * yang memetakannya; serial yang tidak dikenal dilaporkan sebagai ONU liar
 * (perangkat terpasang di lapangan tapi belum terdaftar).
 */
data class OnuReading(
    val serialNumber: String,
    val oltCode: String,
    val ponPortLabel: String?,
    val status: OnuOperationalStatus,
    val rxPowerDbm: Double?,
    val txPowerDbm: Double?,
    val uptimeSeconds: Long?,
    /** Jarak hasil ranging OLT dalam meter; berguna untuk memperkirakan titik putus. */
    val distanceMeters: Int?,
    val observedAt: Instant,
    /**
     * Penyebab terakhir ONU putus, dari register "last down cause" OLT. OLT
     * menyimpannya melewati ONU yang sudah kembali online, jadi nilainya bisa hadir
     * meski status kini ONLINE — mencerminkan alasan gangguan terakhir. `null` bila
     * OLT tidak melaporkannya atau ONU belum pernah putus.
     *
     * Pembeda paling berharganya: [OnuDownCause.DYING_GASP] (pelanggan mati listrik)
     * versus [OnuDownCause.LOS] (fiber putus) — dua gangguan yang tampak sama-sama
     * "mati" tapi menuntut tindakan yang sama sekali berbeda.
     */
    val lastDownCause: OnuDownCause? = null,
    /**
     * Kapan ONU terakhir kali tercatat putus di register OLT. Seperti
     * [lastDownCause], nilainya bertahan melewati pemulihan, jadi bisa hadir meski
     * status kini ONLINE. `null` bila OLT tidak melaporkannya atau ONU belum pernah
     * putus. Berpasangan dengan [lastOnAt] menjadi durasi gangguan terakhir.
     */
    val lastOffAt: Instant? = null,
    /**
     * Kapan ONU terakhir kali tercatat kembali online di register OLT. Bila lebih
     * baru dari [lastOffAt], ONU sudah pulih; bila lebih lama (atau `null` saat
     * [lastOffAt] terisi), ONU masih putus sejak saat itu.
     */
    val lastOnAt: Instant? = null,
)

/** Status ONU sebagaimana dilaporkan OLT. */
enum class OnuOperationalStatus {
    ONLINE,
    OFFLINE,
    /** Loss of Signal — fiber putus atau konektor lepas. */
    LOS,
    /** Dikenali OLT tapi belum terotorisasi. */
    UNKNOWN,
}

/**
 * Alasan sebuah ONU terakhir kali putus, sebagaimana dilaporkan OLT.
 *
 * Membedakan gangguan yang di layar tampak identik ("mati") tapi akar & tindakannya
 * beda: [DYING_GASP] cukup tunggu listrik pelanggan pulih, [LOS] harus kirim teknisi.
 */
enum class OnuDownCause {
    /** ONT mengirim "dying gasp" saat kehilangan daya — pelanggan mati listrik. */
    DYING_GASP,
    /** Loss of Signal — fiber putus atau konektor lepas. */
    LOS,
    /** Loss of Burst — jendela transmisi ONU hilang. */
    LOB,
    /** Sinyal terlalu lemah/rusak untuk dikunci. */
    SIGNAL_FAIL,
    /** Dinonaktifkan operator dari sisi OLT. */
    ADMIN_DOWN,
    /** OLT melaporkan putus tanpa sebab yang bisa dipetakan. */
    UNKNOWN,
}

// ---------------------------------------------------------------------------
// Server → collector
// ---------------------------------------------------------------------------

/**
 * Konfigurasi yang dikembalikan server pada tiap denyut. Collector memperlakukan
 * ini sebagai kebenaran mutlak dan mengganti konfigurasi lokalnya.
 */
data class CollectorConfig(
    val collectorName: String,
    val pollIntervalSeconds: Int,
    val targets: List<OltTarget>,
    /** Server bisa menyuruh collector diam, mis. saat pemeliharaan. */
    val paused: Boolean = false,
    /**
     * BRAS/NAS yang harus di-polling untuk sesi PPPoE & akunting (Phase 7).
     * Aditif dan kosong bila tenant belum memakai modul BNG — collector lama yang
     * belum mengenal field ini mengabaikannya, sesuai kebijakan forward-compatible.
     */
    val nasTargets: List<NasTarget> = emptyList(),
    /**
     * Perintah BRAS yang harus dieksekusi collector pada siklus ini (Phase 7c):
     * memutus sesi (Reset Login/isolir) atau mengubah kecepatan sesi hidup (CoA).
     * Inilah jalur TURUN — arah perintah dari server ke collector — yang menumpang
     * respons denyut agar tetap outbound-only. Aditif; agent lama mengabaikannya.
     * Collector menjalankannya lalu meng-ACK lewat [CollectorHeartbeat.actionResults].
     */
    val bngActions: List<BngActionCommand> = emptyList(),
)

/**
 * Satu OLT yang harus di-polling.
 *
 * [snmpCommunity] dikirim polos di dalam badan respons — aman karena kanalnya
 * TLS dan hanya collector terautentikasi yang bisa memintanya. Di database
 * server nilainya tetap tersimpan terenkripsi.
 */
data class OltTarget(
    val oltId: String,
    val oltCode: String,
    val vendor: String,
    val host: String,
    val snmpPort: Int = 161,
    val snmpCommunity: String?,
)

/**
 * Satu BRAS/NAS yang harus di-polling collector untuk sesi PPPoE.
 *
 * [expectedUsernames] adalah akun yang menurut server seharusnya online — dipakai
 * HANYA oleh adapter simulator untuk memproduksi sesi yang cocok dengan pelanggan
 * nyata. Adapter sungguhan (RouterOS) mengabaikannya dan membaca sesi apa adanya dari
 * perangkat. [adapterType] memilih adapter di sisi collector.
 *
 * Kredensial kontrol ([apiUsername]..[coaSecret]) dikirim polos di dalam badan
 * respons — aman karena kanalnya TLS dan hanya collector terautentikasi yang bisa
 * memintanya, persis seperti [OltTarget.snmpCommunity]. Di database server nilainya
 * tetap terenkripsi. RouterOS memakai [apiUsername]/[apiSecret]/[apiPort]/[apiUseTls]
 * untuk REST API v7 dan [coaSecret] untuk paket Disconnect/CoA (RFC 5176) ke [host].
 * Semuanya opsional/berdefault agar forward-compatible: collector lama mengabaikannya.
 */
data class NasTarget(
    val nasId: String,
    val name: String,
    val vendor: String,
    val host: String?,
    val adapterType: String,
    val expectedUsernames: List<String> = emptyList(),
    val apiUsername: String? = null,
    val apiSecret: String? = null,
    val apiPort: Int? = null,
    val apiUseTls: Boolean = true,
    val coaSecret: String? = null,
)

/** Jawaban server atas sebuah [MetricBatch]. */
data class IngestResult(
    val accepted: Int,
    /** Bacaan yang serialnya tidak dikenal — kandidat ONU liar. */
    val unknownSerialNumbers: List<String>,
    /** True bila [MetricBatch.batchId] sudah pernah diterima. */
    val duplicate: Boolean = false,
)

// ---------------------------------------------------------------------------
// BNG (BRAS/RADIUS) — sesi PPPoE, Phase 7
// ---------------------------------------------------------------------------

/**
 * Kiriman batch sesi PPPoE dari satu BRAS. Seperti [MetricBatch], [batchId] diulang
 * saat pengiriman gagal sehingga server bisa membuang kiriman ganda.
 */
data class BngSessionBatch(
    val batchId: String,
    val nasId: String,
    val collectedAt: Instant,
    val sessions: List<RadiusSessionReading>,
) {
    companion object {
        const val MAX_SESSIONS = 5_000
    }
}

/**
 * Satu sesi PPPoE sebagaimana dilaporkan BRAS.
 *
 * Octet KUMULATIF sejak sesi dimulai (bukan delta): server menghitung laju Mbps
 * dari selisih antar-poll, meniru cara akunting RADIUS bekerja. [inOctets] = arah
 * unggah pelanggan (masuk ke BRAS), [outOctets] = unduh (keluar dari BRAS ke
 * pelanggan). ONU dikenali lewat [username] — server memetakannya ke akun; username
 * yang tak dikenal berarti sesi milik langganan yang belum diprovisi di sistem.
 */
data class RadiusSessionReading(
    val username: String,
    val online: Boolean,
    val framedIp: String? = null,
    val nasIp: String? = null,
    val sessionId: String? = null,
    val callingStationId: String? = null,
    val uptimeSeconds: Long? = null,
    val inOctets: Long? = null,
    val outOctets: Long? = null,
)

/** Jawaban server atas sebuah [BngSessionBatch]. */
data class BngIngestResult(
    val accepted: Int,
    /** Username sesi yang tak cocok akun mana pun — langganan belum diprovisi. */
    val unknownUsernames: List<String> = emptyList(),
    val duplicate: Boolean = false,
)

/**
 * Jenis perintah BRAS/RADIUS yang bisa dititipkan server ke collector.
 *
 * Dua jalur lewat kanal berbeda:
 *  - **DAE** ([DISCONNECT]/[COA]) — paket RFC 5176 langsung ke BRAS penutup sesi;
 *  - **provisioning SQL** ([PROVISION]/[DEPROVISION]/[SYNC_GROUP]) — tulis tabel
 *    otorisasi FreeRADIUS (`radcheck`/`radusergroup`/`radgroupreply`) via JDBC.
 *    Inilah jalur "RADIUS jadi pusat": bikin/ubah paket = satu baris grup, bukan
 *    sentuh tiap router.
 *
 * Aditif terhadap protokol lama: collector yang belum mengenal kind baru melaporkannya
 * gagal (jujur), server menyimpan sebabnya — bukan diam-diam tak berefek.
 */
enum class BngActionKind {
    /** Putuskan sesi PPPoE (Disconnect-Request/PoD) — dasar Reset Login & pemotongan isolir. */
    DISCONNECT,

    /** Change-of-Authorization: ubah kecepatan sesi yang sedang hidup tanpa memutusnya. */
    COA,

    /** Tulis kredensial + keanggotaan grup akun (radcheck Cleartext-Password + radusergroup). */
    PROVISION,

    /** Hapus kredensial + keanggotaan + balasan akun (radcheck/radreply/radusergroup by username). */
    DEPROVISION,

    /** Sinkronkan atribut grup paket (radgroupreply Mikrotik-Rate-Limit + Simultaneous-Use + grup FUP). */
    SYNC_GROUP,
}

/**
 * Satu perintah BRAS/RADIUS yang harus dieksekusi collector, Phase 7c (diperluas untuk
 * provisioning RADIUS-pusat).
 *
 * [actionId] dibuat server dan dipantulkan collector di ACK sehingga server bisa
 * menuntaskan tepat perintah itu (dan mengenali kiriman ganda — perintah dikirim ulang
 * tiap denyut sampai di-ACK, jadi eksekusinya harus idempoten). [username] menyasar sesi
 * (DAE) atau baris otorisasi (SQL) yang tepat.
 *
 * Field payload terisi sesuai [kind]; sisanya null:
 *  - [downMbps]/[upMbps] — [BngActionKind.COA] (juga dipakai adapter untuk atribut rate live).
 *  - [groupname] — [BngActionKind.PROVISION] (grup yang diikuti akun) & [BngActionKind.SYNC_GROUP]
 *    (grup yang disetel).
 *  - [password] — [BngActionKind.PROVISION] saja; TIDAK disimpan server, diresolusi+dekripsi
 *    saat klaim dan hanya melintas kanal TLS — tak ada cleartext at-rest baru.
 *  - [rateLimit]/[simultaneousUse]/[fupGroupname]/[fupRateLimit] — [BngActionKind.SYNC_GROUP].
 */
data class BngActionCommand(
    val actionId: String,
    val nasId: String,
    val kind: BngActionKind,
    val username: String,
    val downMbps: Int? = null,
    val upMbps: Int? = null,
    val groupname: String? = null,
    val password: String? = null,
    val rateLimit: String? = null,
    val simultaneousUse: Int? = null,
    val fupGroupname: String? = null,
    val fupRateLimit: String? = null,
)

/**
 * Hasil eksekusi satu [BngActionCommand], dikirim balik pada denyut berikutnya.
 * [success] false menyertakan [detail] sebab gagal (mis. BRAS menolak CoA), yang
 * server simpan di jejak perintah agar operator tahu kenapa Reset Login tak berhasil.
 */
data class BngActionResult(
    val actionId: String,
    val success: Boolean,
    val detail: String? = null,
)

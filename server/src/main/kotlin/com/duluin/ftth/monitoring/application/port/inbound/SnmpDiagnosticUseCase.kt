package com.duluin.ftth.monitoring.application.port.inbound

import java.time.Instant
import java.util.UUID

/**
 * Alat validasi OID di lapangan.
 *
 * Peta MIB tiap vendor disusun dari dokumentasi, dan firmware berbeda kerap menggeser
 * sub-tree. Kalau satu OID meleset, polling tak melempar error apa pun — ia hanya diam
 * mengembalikan nol baris, dan OLT itu tampak "sehat tapi tak punya ONU". Kegagalan
 * senyap semacam ini hanya bisa dipatahkan dengan menanyai perangkat sungguhan.
 *
 * Alat ini menyuruh SERVER yang bertanya, memakai kredensial OLT yang sudah tersimpan,
 * sehingga teknisi di lokasi cukup membuka halaman OLT — tanpa `snmpwalk`, tanpa akses
 * shell ke server, dan tanpa community string berpindah tangan.
 */
interface SnmpDiagnosticUseCase {

    /**
     * Menguji seluruh OID yang dipakai adapter vendor OLT ini terhadap perangkat
     * sungguhan, lengkap dengan contoh nilai mentah dan tafsirannya.
     */
    fun checkOidPlan(oltId: UUID): OltSnmpCheck

    /**
     * Men-walk sub-tree OID bebas pada OLT yang SUDAH ada di inventory — untuk berburu
     * OID yang benar ketika profil vendor meleset.
     *
     * Sengaja tak menerima host/community dari pemanggil: sasarannya selalu perangkat
     * milik tenant yang bersangkutan, sehingga endpoint ini tak bisa dipakai memindai
     * host sembarangan dari dalam jaringan server.
     */
    fun walk(oltId: UUID, rootOid: String, limit: Int): OltSnmpWalk
}

/**
 * Hasil pemeriksaan peta OID satu OLT. [supported] `false` berarti vendornya belum punya
 * adapter sama sekali (perangkat boleh tetap ada di inventory, hanya belum bisa dimonitor);
 * [reachable] `false` berarti perangkatnya yang tak menjawab, sehingga [oids] kosong —
 * tak ada gunanya menilai OID pada perangkat yang bahkan tak menyapa balik.
 */
data class OltSnmpCheck(
    val oltId: UUID,
    val oltCode: String,
    val vendor: String,
    val supported: Boolean,
    val reachable: Boolean,
    val systemDescription: String?,
    val roundTripMillis: Long?,
    val failureReason: String?,
    val checkedAt: Instant,
    val oids: List<OidCheck>,
)

/** Nasib satu OID pada perangkat ini, beserta saran tindak lanjutnya. */
data class OidCheck(
    val role: String,
    val label: String,
    val oid: String?,
    /** Tanpa OID ini polling tak menghasilkan baris apa pun. */
    val essential: Boolean,
    val verdict: OidVerdict,
    val sampleCount: Int,
    val samples: List<OidSampleView>,
    val hint: String?,
)

/**
 * - [OK] — perangkat menjawab dan nilainya masuk akal menurut aturan vendor.
 * - [EMPTY] — sub-tree kosong: OID-nya salah untuk firmware ini, atau fiturnya mati.
 * - [UNREADABLE] — menjawab, tapi TAK SATU pun nilainya bisa ditafsirkan: biasanya
 *   skala/satuan atau pemetaan status yang berbeda. Ini kegagalan yang paling licin,
 *   karena polling akan "berhasil" tapi mengisi metrik dengan nilai kosong.
 * - [NOT_CONFIGURED] — profil vendor kami memang belum memuat OID ini.
 */
enum class OidVerdict { OK, EMPTY, UNREADABLE, NOT_CONFIGURED }

/** Satu contoh nilai; [interpreted] `null` bila aturan vendor tak bisa membacanya. */
data class OidSampleView(
    val index: String,
    val raw: String,
    val interpreted: String?,
)

/** Hasil walk OID bebas; [truncated] menandai daftar yang dipotong oleh `limit`. */
data class OltSnmpWalk(
    val oltId: UUID,
    val oltCode: String,
    val rootOid: String,
    val sampleCount: Int,
    val truncated: Boolean,
    val elapsedMillis: Long,
    val rows: List<SnmpWalkRow>,
)

/** [oid] sudah berupa OID penuh (akar + indeks) supaya bisa disalin apa adanya. */
data class SnmpWalkRow(
    val oid: String,
    val value: String,
)

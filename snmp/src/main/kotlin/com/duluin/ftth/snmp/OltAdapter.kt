package com.duluin.ftth.snmp

import com.duluin.ftth.contract.OltTarget
import com.duluin.ftth.contract.OnuReading

/**
 * Kontrak untuk berbicara dengan satu jenis OLT.
 *
 * Inilah titik di mana perbedaan vendor diisolasi. Setiap vendor memakai MIB,
 * OID, dan satuan yang berbeda — ZTE melaporkan redaman dalam 0,001 dBm sementara
 * Huawei dalam 0,01 dBm — dan semua keanehan itu berhenti di sini. Bagian lain
 * collector maupun server hanya pernah melihat [OnuReading] yang sudah seragam.
 *
 * Menambah vendor baru berarti menambah satu implementasi, bukan menyentuh
 * loop polling.
 */
interface OltAdapter {

    /** Nama vendor sebagaimana dikenal server, mis. `ZTE`. */
    val vendor: String

    /**
     * Memastikan perangkat bisa dihubungi sebelum polling penuh dijalankan.
     * Dipisah agar OLT yang mati bisa dilaporkan cepat tanpa menunggu seluruh
     * walk SNMP kehabisan waktu.
     */
    fun probe(target: OltTarget): ProbeResult

    /** Membaca seluruh ONU di bawah OLT ini beserta metrik optiknya. */
    fun pollOnus(target: OltTarget): List<OnuReading>

    /**
     * OID yang dipakai adapter ini beserta perannya — bahan alat validasi OID di lapangan.
     *
     * Peta MIB kami disusun dari dokumentasi vendor, dan firmware berbeda kerap menggeser
     * sub-tree: satu OID meleset dan seluruh polling diam-diam mengembalikan nol baris tanpa
     * error. Dengan rencana ini, operator yang berdiri di depan OLT sungguhan bisa menyuruh
     * server men-walk tiap OID dan melihat mana yang menjawab — tanpa perlu akses shell ke
     * server maupun ke perangkat.
     *
     * Baku kosong: adapter yang tak punya OID untuk divalidasi (mis. simulator) tak perlu
     * berpura-pura punya.
     */
    val oidPlan: List<OidRole> get() = emptyList()
}

/**
 * Satu OID beserta perannya dalam peta MIB sebuah vendor.
 *
 * [role] adalah kode stabil untuk klien (SERIAL/STATUS/RX_POWER/…), [label] teks siap tampil.
 * [oid] `null` berarti vendor ini belum diketahui OID-nya — bukan error, justru daftar
 * pekerjaan berikutnya. [essential] menandai OID yang bila kosong membuat polling tak
 * menghasilkan apa pun, sehingga alat validasi bisa membedakan "kurang lengkap" dari "rusak".
 *
 * [interpret] menerjemahkan satu nilai mentah menjadi bentuk yang dimengerti manusia (heksa
 * serial → `ZTEGC0FFEE01`, `3` → `ONLINE`, `-2350` → `-23.5 dBm`) memakai penafsir milik
 * adapter itu sendiri. Justru penafsiran inilah yang paling perlu dilihat mata manusia:
 * OID bisa saja menjawab, tapi satuan atau skalanya beda antar firmware. Hasil `null`
 * berarti nilai mentahnya TAK cocok dengan aturan vendor sekarang — sinyal paling berharga
 * dari alat ini. Baku: nilai mentah diteruskan apa adanya (tak ada aturan yang bisa gagal).
 */
data class OidRole(
    val role: String,
    val label: String,
    val oid: String?,
    val essential: Boolean = false,
    val interpret: (String) -> String? = { it },
)

sealed interface ProbeResult {
    data class Reachable(val systemDescription: String?, val roundTripMillis: Long) : ProbeResult
    data class Unreachable(val reason: String) : ProbeResult
}

/**
 * Dilempar adapter ketika perangkat menjawab tapi jawabannya tidak masuk akal —
 * dibedakan dari perangkat yang tidak bisa dihubungi, karena penanganannya beda:
 * yang satu masalah jaringan, yang satu masalah kecocokan firmware/MIB.
 */
class OltProtocolException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * Memilih adapter sesuai vendor OLT.
 *
 * OLT dengan vendor yang tidak dikenal sengaja menghasilkan `null`, bukan
 * exception: perangkat semacam itu tetap boleh ada di inventory (didata,
 * dipetakan) hanya saja belum bisa dimonitor otomatis.
 */
class AdapterRegistry(adapters: List<OltAdapter>) {

    private val byVendor = adapters.associateBy { it.vendor.uppercase() }

    fun forVendor(vendor: String): OltAdapter? = byVendor[vendor.uppercase()]

    val supportedVendors: Set<String> get() = byVendor.keys
}

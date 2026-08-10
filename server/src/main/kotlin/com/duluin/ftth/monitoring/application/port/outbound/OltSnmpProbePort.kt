package com.duluin.ftth.monitoring.application.port.outbound

/**
 * Percakapan SNMP mentah dengan satu perangkat, untuk keperluan DIAGNOSTIK.
 *
 * Dipisahkan dari jalur polling (`OltAdapter` di modul `:snmp`, yang mengembalikan
 * [com.duluin.ftth.contract.OnuReading] sudah tertafsir) karena tujuannya berlawanan:
 * polling ingin data seragam dan membuang yang aneh, diagnostik justru ingin melihat
 * nilai mentahnya — termasuk yang aneh, karena di situlah letak salahnya.
 *
 * Sebagai port keluar, implementasinya (UDP nyata) tinggal di adapter, sehingga logika
 * penilaian OID bisa diuji tanpa perangkat maupun soket.
 */
interface OltSnmpProbePort {

    /**
     * Menyapa perangkat: baca `sysDescr` dan catat waktu bolak-baliknya. Dipisah dari
     * [walk] supaya perangkat mati ketahuan cepat, tanpa menunggu walk kehabisan waktu.
     *
     * @throws SnmpProbeFailure bila perangkat tak menjawab (jaringan/community salah).
     */
    fun greet(target: SnmpProbeTarget): SnmpGreeting

    /**
     * Men-walk satu atau beberapa sub-tree OID sekaligus dan mengembalikan nilai
     * mentahnya per OID akar. Sekali walk untuk semua OID agar nilainya berasal dari
     * saat yang kurang lebih sama — sama alasannya dengan polling.
     *
     * OID yang tak dijawab perangkat menghasilkan daftar kosong, BUKAN error: "sub-tree
     * ini kosong" justru jawaban yang dicari alat validasi.
     *
     * @throws SnmpProbeFailure bila walk-nya sendiri gagal (perangkat putus di tengah).
     */
    fun walk(target: SnmpProbeTarget, rootOids: List<String>): Map<String, List<SnmpSample>>
}

/** Alamat perangkat beserta community-nya. Tak pernah keluar dari server. */
data class SnmpProbeTarget(
    val host: String,
    val port: Int,
    val community: String,
)

/** Jawaban sapaan: identitas perangkat apa adanya + waktu bolak-balik. */
data class SnmpGreeting(
    val systemDescription: String?,
    val roundTripMillis: Long,
)

/** Satu nilai mentah hasil walk; [index] adalah sisa OID di belakang akar. */
data class SnmpSample(
    val index: String,
    val value: String,
)

/**
 * Perangkat tak bisa diajak bicara — dibedakan dari "menjawab tapi kosong", karena
 * tindak lanjutnya beda: yang satu urusan jaringan/kredensial, yang satu urusan OID.
 */
class SnmpProbeFailure(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

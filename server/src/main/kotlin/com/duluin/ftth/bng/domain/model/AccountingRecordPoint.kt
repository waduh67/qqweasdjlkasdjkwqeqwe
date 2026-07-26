package com.duluin.ftth.bng.domain.model

import java.time.Instant
import java.util.UUID

/**
 * Satu cuplikan akunting RADIUS untuk sebuah akun pada satu waktu — deret waktu
 * (hypertable), sumber tren trafik.
 *
 * Yang disimpan adalah penghitung KUMULATIF (octet & uptime naik terus selama sesi),
 * persis seperti dilaporkan BRAS. Laju sesungguhnya (Mbps) TIDAK disimpan melainkan
 * dihitung di query dari selisih dua cuplikan berurutan dibagi selisih waktunya —
 * lihat [TrafficSample]. Menyimpan mentahnya membuat satu penghitung yang ter-reset
 * (mis. BRAS restart) tidak merusak titik-titik lain.
 *
 * [inOctets] = arah pelanggan→jaringan (Up), [outOctets] = jaringan→pelanggan (Down).
 */
data class AccountingRecordPoint(
    val time: Instant,
    val tenantId: UUID,
    val subscriberAccessId: UUID,
    val nasId: UUID?,
    val inOctets: Long?,
    val outOctets: Long?,
    val uptimeSeconds: Long?,
)

/**
 * Satu titik tren trafik siap-tampil: laju rata-rata pada rentang antara cuplikan
 * sebelumnya dan [time], sudah dalam Mbps. Null berarti tak terhitung di titik itu
 * (cuplikan pertama, atau penghitung ter-reset) — grafik memutus garisnya, bukan
 * menggambar nol palsu.
 */
data class TrafficSample(
    val time: Instant,
    val downMbps: Double?,
    val upMbps: Double?,
)

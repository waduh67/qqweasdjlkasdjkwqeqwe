package com.duluin.ftth.collector.adapter

import com.duluin.ftth.contract.BngActionCommand
import com.duluin.ftth.contract.NasTarget
import com.duluin.ftth.contract.RadiusSessionReading
import org.slf4j.LoggerFactory
import java.time.Instant
import kotlin.math.abs

/**
 * BRAS tiruan untuk pengembangan dan pengujian.
 *
 * Bukan sekadar angka acak: perilakunya dibuat menyerupai akunting RADIUS nyata
 * agar jalur baca server (sesi terkini + tren trafik) benar-benar teruji.
 *
 * - Untuk tiap username yang server harapkan online ([NasTarget.expectedUsernames]),
 *   dihasilkan satu sesi dengan IP & MAC deterministik.
 * - Penghitung octet TUMBUH seiring uptime (kumulatif, seperti BRAS sungguhan),
 *   sehingga laju Mbps yang dihitung server dari selisih antar-poll masuk akal dan
 *   stabil antar-siklus.
 * - Sebagian kecil pelanggan sengaja dibuat offline agar panel BRAS menampilkan
 *   kedua keadaan.
 *
 * Deterministik: username yang sama selalu memetakan ke IP & laju yang sama, jadi
 * dua poll berturut hanya berbeda pada octet yang bertambah — persis yang
 * dibutuhkan perhitungan delta/waktu di server.
 */
class SimulatorBngAdapter(
    override val vendor: String = VENDOR,
    private val clock: () -> Instant = Instant::now,
) : BngAdapter {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun pollSessions(target: NasTarget): List<RadiusSessionReading> {
        val now = clock()
        return target.expectedUsernames.map { username -> reading(username, target, now) }
    }

    /**
     * BRAS tiruan selalu "berhasil" mengeksekusi: tak ada perangkat nyata untuk
     * memutus/mengubah. Cukup catat agar jalur perintah ujung-ke-ujung teramati saat
     * dev — efek nyatanya tampak di poll berikutnya (akun ISOLATED hilang dari
     * expectedUsernames sehingga tak lagi dilaporkan online).
     */
    override fun execute(target: NasTarget, action: BngActionCommand) {
        log.info(
            "SIMULATOR BRAS {}: {} untuk {}{}",
            target.name,
            action.kind,
            action.username,
            if (action.kind.name == "COA") " → ${action.downMbps}/${action.upMbps} Mbps" else "",
        )
    }

    private fun reading(username: String, target: NasTarget, now: Instant): RadiusSessionReading {
        val h = abs(username.hashCode())

        // ~1 dari 9 pelanggan offline, stabil per-username.
        if (h % 9 == 0) return RadiusSessionReading(username = username, online = false)

        // Laju dasar per pelanggan (Mbps), stabil per-username.
        val downMbps = 5 + h % 45 // 5..49
        val upMbps = 1 + h % 12 // 1..12

        // Octet KUMULATIF diikat ke jam dinding (byte-per-detik × epoch), bukan ke
        // uptime, supaya selisih antar dua poll = laju × selang waktu sebenarnya —
        // itulah yang membuat Mbps hasil hitung server stabil. Nilai absolutnya tak
        // pernah tampil (UI hanya melihat Mbps), hanya selisihnya yang dipakai.
        val epoch = now.epochSecond

        return RadiusSessionReading(
            username = username,
            online = true,
            framedIp = "10.${(h shr 16) % 254 + 1}.${(h shr 8) % 254 + 1}.${h % 254 + 1}",
            nasIp = target.host?.takeIf { it.isNotBlank() } ?: "10.0.0.1",
            sessionId = "sim-%08x".format(h),
            callingStationId = macFor(h),
            // Uptime terpisah dari octet: nilai tampilan yang wajar & stabil per-username.
            uptimeSeconds = 3_600L + (h % 82_800L),
            outOctets = mbpsToBytesPerSecond(downMbps) * epoch,
            inOctets = mbpsToBytesPerSecond(upMbps) * epoch,
        )
    }

    private fun mbpsToBytesPerSecond(mbps: Int): Long = mbps.toLong() * 1_000_000L / 8L

    private fun macFor(h: Int): String =
        (0..5).joinToString(":") { "%02X".format((h shr (it * 4)) and 0xFF) }

    companion object {
        const val VENDOR = "SIMULATOR"
    }
}

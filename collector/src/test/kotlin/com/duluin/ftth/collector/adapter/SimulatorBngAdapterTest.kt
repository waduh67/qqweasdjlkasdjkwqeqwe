package com.duluin.ftth.collector.adapter

import com.duluin.ftth.contract.BngActionCommand
import com.duluin.ftth.contract.BngActionKind
import com.duluin.ftth.contract.NasTarget
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Menguji BRAS tiruan tanpa perangkat: satu sesi per username yang diharapkan,
 * identitas sesi deterministik, penghitung octet KUMULATIF yang—dibagi selang
 * waktu—menghasilkan laju Mbps stabil dan wajar, serta campuran online/offline.
 *
 * Inilah kontrak yang membuat jalur baca server (sesi terkini + tren trafik)
 * benar-benar teruji ujung-ke-ujung memakai data sintetis, bukan angka acak.
 */
class SimulatorBngAdapterTest {

    private fun targetOf(vararg usernames: String, host: String? = "10.9.9.9") = NasTarget(
        nasId = "nas-1",
        name = "BRAS-SIM",
        vendor = "MIKROTIK",
        host = host,
        adapterType = "SIMULATOR",
        expectedUsernames = usernames.toList(),
    )

    private fun adapterAt(instant: Instant) = SimulatorBngAdapter(clock = { instant })

    @Test
    fun `setiap username yang diharapkan menghasilkan tepat satu sesi, urut`() {
        val sessions = adapterAt(Instant.parse("2026-07-26T00:00:00Z"))
            .pollSessions(targetOf("a@isp", "b@isp", "c@isp"))
        assertEquals(listOf("a@isp", "b@isp", "c@isp"), sessions.map { it.username })
    }

    @Test
    fun `selisih octet antar dua poll dibagi waktu menghasilkan laju Mbps yang wajar dan stabil`() {
        // Ambil banyak kandidat lalu pilih yang pasti online, agar skenario laju
        // bebas dari keacakan offline per-username.
        val candidates = (1..30).map { "rate$it@isp" }
        val t0 = Instant.parse("2026-07-26T00:00:00Z")
        val seconds = 10L
        val before = adapterAt(t0).pollSessions(targetOf(*candidates.toTypedArray()))
        val after = adapterAt(t0.plusSeconds(seconds)).pollSessions(targetOf(*candidates.toTypedArray()))

        val onlineIdx = before.indexOfFirst { it.online }
        assertTrue(onlineIdx >= 0, "harus ada minimal satu sesi online")
        val r0 = before[onlineIdx]
        val r1 = after[onlineIdx]

        // Persis rumus yang dipakai server: Δoctet × 8 ÷ detik ÷ 1e6.
        val downMbps = (r1.outOctets!! - r0.outOctets!!) * 8.0 / seconds / 1_000_000.0
        val upMbps = (r1.inOctets!! - r0.inOctets!!) * 8.0 / seconds / 1_000_000.0
        assertTrue(downMbps in 5.0..49.0, "down $downMbps di luar band simulator")
        assertTrue(upMbps in 1.0..12.0, "up $upMbps di luar band simulator")

        // Identitas deterministik: hanya octet yang berubah antar-poll.
        assertEquals(r0.framedIp, r1.framedIp)
        assertEquals(r0.sessionId, r1.sessionId)
        assertEquals(r0.callingStationId, r1.callingStationId)
        assertTrue(r1.outOctets!! > r0.outOctets!!, "octet kumulatif harus tumbuh")
    }

    @Test
    fun `menghasilkan campuran sesi online dan offline dengan bentuk yang konsisten`() {
        val usernames = (1..60).map { "user$it@isp" }
        val sessions = adapterAt(Instant.parse("2026-07-26T00:00:00Z"))
            .pollSessions(targetOf(*usernames.toTypedArray()))

        val online = sessions.filter { it.online }
        val offline = sessions.filterNot { it.online }
        assertTrue(online.isNotEmpty(), "harus ada sesi online")
        assertTrue(offline.isNotEmpty(), "harus ada sesi offline")

        // Online berisi identitas lengkap; offline hanya penanda mati (sisanya kosong).
        online.forEach {
            assertNotNull(it.framedIp)
            assertNotNull(it.sessionId)
            assertNotNull(it.callingStationId)
        }
        offline.forEach {
            assertNull(it.framedIp)
            assertNull(it.sessionId)
        }
    }

    @Test
    fun `execute DISCONNECT dan COA selalu berhasil dan idempoten tanpa melempar`() {
        val adapter = adapterAt(Instant.parse("2026-07-26T00:00:00Z"))
        val target = targetOf("budi@isp")

        // Simulator tak punya perangkat nyata untuk dipotong: sukses = tak melempar.
        // Pemanggil menerjemahkan "tak melempar" menjadi ACK sukses; efek nyatanya
        // (akun ISOLATED lenyap dari expectedUsernames) baru tampak di poll berikutnya.
        adapter.execute(
            target,
            BngActionCommand(actionId = "a1", nasId = "nas-1", kind = BngActionKind.DISCONNECT, username = "budi@isp"),
        )
        // CoA membawa kecepatan baru; simulator cukup mencatatnya tanpa gagal.
        adapter.execute(
            target,
            BngActionCommand("a2", "nas-1", BngActionKind.COA, "budi@isp", downMbps = 50, upMbps = 20),
        )
        // Idempoten: perintah yang sama datang lagi (at-least-once) tetap aman.
        adapter.execute(
            target,
            BngActionCommand(actionId = "a1", nasId = "nas-1", kind = BngActionKind.DISCONNECT, username = "budi@isp"),
        )
    }

    @Test
    fun `nasIp jatuh ke bawaan saat host BRAS kosong`() {
        val usernames = (1..20).map { "nasip$it@isp" }
        val online = adapterAt(Instant.parse("2026-07-26T00:00:00Z"))
            .pollSessions(targetOf(*usernames.toTypedArray(), host = null))
            .first { it.online }
        assertEquals("10.0.0.1", online.nasIp)
    }
}

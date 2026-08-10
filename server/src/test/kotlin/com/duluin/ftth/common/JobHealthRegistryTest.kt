package com.duluin.ftth.common

import com.duluin.ftth.common.infrastructure.observability.JobHealthRegistry
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

/**
 * Aritmetika "macet" — bagian yang menentukan kapan seseorang dibangunkan tengah malam,
 * jadi ia diuji dengan waktu yang dikendalikan, bukan dengan menunggu.
 */
class JobHealthRegistryTest {

    private val bootedAt: Instant = Instant.parse("2026-08-10T00:00:00Z")

    private fun registry(factor: Long = 3, grace: Duration = Duration.ofMinutes(10)) =
        JobHealthRegistry(factor, grace, bootedAt)

    private fun method(name: String) = Probe::class.java.getDeclaredMethod(name)

    /** Bentuk yang dipakai penemuan job terjadwal: `paket.Kelas.metode`. */
    private fun qualified(name: String) = "${Probe::class.java.name}.$name"

    @Suppress("unused")
    private class Probe {
        fun poll() = Unit
        fun sweep() = Unit
    }

    @Test
    fun `job yang baru dideklarasikan belum dianggap macet`() {
        val registry = registry()
        registry.declare(qualified("poll"), Duration.ofMinutes(5))

        val job = registry.health("Probe.poll", bootedAt.plusSeconds(60))!!

        assertThat(job.module).isEqualTo("common")
        assertThat(job.runs).isZero()
        assertThat(job.stalled).isFalse()
        // Ambangnya interval × faktor: 5 menit × 3 = 15 menit, di atas lantai 10 menit.
        assertThat(job.stallAfter).isEqualTo(Duration.ofMinutes(15))
    }

    @Test
    fun `job yang tak pernah sukses jadi macet setelah ambang, dihitung sejak boot`() {
        val registry = registry()
        registry.declare(qualified("poll"), Duration.ofMinutes(5))

        assertThat(registry.health("Probe.poll", bootedAt.plusSeconds(14 * 60))!!.stalled).isFalse()
        assertThat(registry.health("Probe.poll", bootedAt.plusSeconds(16 * 60))!!.stalled).isTrue()
    }

    @Test
    fun `job berinterval detik memakai lantai grace, bukan interval x faktor`() {
        val registry = registry()
        registry.declare(qualified("poll"), Duration.ofSeconds(10))

        // 10 detik × 3 = 30 detik; terlalu galak untuk dijadikan peringatan, jadi lantai
        // 10 menit yang berlaku — satu ronde tersendat tak boleh membangunkan siapa pun.
        assertThat(registry.health("Probe.poll", bootedAt)!!.stallAfter).isEqualTo(Duration.ofMinutes(10))
    }

    @Test
    fun `sukses menyetel ulang umur, gagal tidak`() {
        val registry = registry()
        val probe = Probe()
        registry.declare(qualified("poll"), Duration.ofMinutes(5))

        registry.track(probe, method("poll")) { "ok" }
        // Satu entri, bukan dua: jalur pendaftaran (string) dan jalur eksekusi (objek+metode)
        // harus bermuara ke kunci yang sama, kalau tidak intervalnya tak pernah bertemu
        // denyutnya dan job selamanya tampak "tak diketahui jadwalnya".
        assertThat(registry.snapshot()).hasSize(1)
        val afterSuccess = registry.health("Probe.poll")!!
        assertThat(afterSuccess.runs).isEqualTo(1)
        assertThat(afterSuccess.failures).isZero()
        assertThat(afterSuccess.lastSuccessAt).isNotNull()
        assertThat(afterSuccess.lastDuration).isNotNull()

        assertThatThrownBy {
            registry.track(probe, method("poll")) { throw IllegalStateException("OLT tak terjangkau") }
        }.isInstanceOf(IllegalStateException::class.java)

        val afterFailure = registry.health("Probe.poll")!!
        assertThat(afterFailure.runs).isEqualTo(2)
        assertThat(afterFailure.failures).isEqualTo(1)
        assertThat(afterFailure.lastError).contains("OLT tak terjangkau")
        // Sukses lama TETAP tercatat: itulah patokan "kapan terakhir kali job ini berhasil",
        // dan itu justru paling dibutuhkan ketika ronde-ronde berikutnya berguguran.
        assertThat(afterFailure.lastSuccessAt).isEqualTo(afterSuccess.lastSuccessAt)
    }

    @Test
    fun `job tanpa interval tak pernah dinyatakan macet`() {
        val registry = registry()
        registry.declare(qualified("sweep"), null)

        val job = registry.health("Probe.sweep", bootedAt.plusSeconds(86_400))!!

        assertThat(job.stallAfter).isNull()
        assertThat(job.stalled).isFalse()
    }
}

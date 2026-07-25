package com.duluin.ftth.collector.adapter

import com.duluin.ftth.contract.OltTarget
import com.duluin.ftth.contract.OnuDownCause
import com.duluin.ftth.contract.OnuOperationalStatus
import com.duluin.ftth.contract.OnuReading
import java.time.Instant
import kotlin.math.sin
import kotlin.random.Random

/**
 * OLT tiruan untuk pengembangan dan pengujian.
 *
 * Ini bukan sekadar penghasil angka acak. Perilakunya dibuat menyerupai jaringan
 * sungguhan supaya mesin alarm benar-benar teruji:
 *
 * - redaman tiap ONU bergoyang pelan di sekitar nilai dasarnya (suhu harian),
 *   bukan meloncat-loncat, sehingga alarm yang terpicu berarti sesuatu;
 * - sebagian kecil ONU sengaja diberi redaman memburuk perlahan — inilah pola
 *   yang harus ditangkap deteksi degradasi di Phase 5;
 * - satu ONU dibuat LOS agar jalur alarm putus-fiber ikut terlatih.
 *
 * Serial yang dihasilkan mengikuti format GPON sungguhan sehingga bisa
 * dicocokkan dengan ONU yang didaftarkan lewat UI.
 */
class SimulatorOltAdapter(
    /**
     * Vendor yang diperankan. Simulator MENGGANTIKAN adapter vendor tersebut,
     * bukan berdiri sebagai vendor tersendiri — dengan begitu OLT di inventory
     * tetap tercatat apa adanya (mis. ZTE) dan tidak ada nilai vendor palsu yang
     * bocor ke data produksi.
     */
    override val vendor: String = VENDOR,
    private val onuCount: Int = 24,
    private val seed: Long = 42,
    private val clock: () -> Instant = Instant::now,
) : OltAdapter {

    override fun probe(target: OltTarget): ProbeResult =
        ProbeResult.Reachable("ftth-simulator GPON OLT (${target.oltCode})", roundTripMillis = 1)

    override fun pollOnus(target: OltTarget): List<OnuReading> {
        val now = clock()
        // Deret ditentukan seed + kode OLT, sehingga OLT yang sama selalu
        // menghasilkan populasi ONU yang sama antar siklus — tanpa itu, tiap
        // polling akan tampak seperti perangkat yang berganti-ganti.
        val random = Random(seed + target.oltCode.hashCode())

        return (1..onuCount).map { index ->
            val serial = "SIMU%08X".format(random.nextInt(0x10000000, 0x7FFFFFFF))
            val profile = OnuProfile.of(index, onuCount)
            OnuReading(
                serialNumber = serial,
                oltCode = target.oltCode,
                ponPortLabel = "1/${(index - 1) / 8 + 1}/${(index - 1) % 8 + 1}",
                status = profile.status,
                rxPowerDbm = profile.rxPower(now, index),
                txPowerDbm = profile.takeIf { it.status == OnuOperationalStatus.ONLINE }?.let { 2.1 },
                uptimeSeconds = if (profile.status == OnuOperationalStatus.ONLINE) 86_400L * index else null,
                distanceMeters = if (profile.status == OnuOperationalStatus.LOS) null else 800 + index * 37,
                observedAt = now,
                lastDownCause = profile.downCause,
            )
        }
    }

    /** Karakter satu ONU dalam simulasi, ditentukan posisinya agar stabil antar siklus. */
    private enum class OnuProfile(
        val status: OnuOperationalStatus,
        /**
         * Penyebab putus terakhir, meniru register OLT: ONU OFFLINE dianggap
         * pelanggan mati listrik ([OnuDownCause.DYING_GASP]) dan ONU LOS dianggap
         * fiber putus — persis pembeda yang harus bisa dikenali sistem.
         */
        val downCause: OnuDownCause? = null,
    ) {
        HEALTHY(OnuOperationalStatus.ONLINE),
        /** Redaman memburuk perlahan — sasaran deteksi degradasi. */
        DEGRADING(OnuOperationalStatus.ONLINE),
        /** Sudah melewati ambang kritis dan harus memicu alarm. */
        CRITICAL(OnuOperationalStatus.ONLINE),
        OFFLINE(OnuOperationalStatus.OFFLINE, OnuDownCause.DYING_GASP),
        LOS(OnuOperationalStatus.LOS, OnuDownCause.LOS),
        ;

        fun rxPower(now: Instant, index: Int): Double? {
            if (this == OFFLINE || this == LOS) return null
            // Ayunan harian ±0,4 dB, fase digeser per ONU supaya tidak serempak.
            val daily = sin((now.epochSecond % 86_400) / 86_400.0 * 2 * Math.PI + index) * 0.4
            val base = when (this) {
                HEALTHY -> -21.0
                DEGRADING -> -25.6
                CRITICAL -> -28.4
                else -> return null
            }
            return Math.round((base + daily) * 100) / 100.0
        }

        companion object {
            /**
             * Sebagian besar ONU sehat; sisanya bermasalah dengan proporsi yang
             * mirip jaringan nyata — kalau setengahnya rusak, alarm jadi bising
             * dan tidak ada yang bisa diuji dengan bermakna.
             */
            fun of(index: Int, total: Int): OnuProfile = when {
                index == total -> LOS
                index == total - 1 -> OFFLINE
                index == total - 2 -> CRITICAL
                index % 7 == 0 -> DEGRADING
                else -> HEALTHY
            }
        }
    }

    companion object {
        const val VENDOR = "SIMULATOR"
    }
}

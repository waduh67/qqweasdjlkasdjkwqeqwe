package com.duluin.ftth.collector.adapter.snmp

import com.duluin.ftth.collector.adapter.OltAdapter
import com.duluin.ftth.collector.adapter.OltProtocolException
import com.duluin.ftth.collector.adapter.ProbeResult
import com.duluin.ftth.contract.OltTarget
import com.duluin.ftth.contract.OnuOperationalStatus
import com.duluin.ftth.contract.OnuReading
import org.slf4j.LoggerFactory
import java.time.Instant

/**
 * Peta MIB satu vendor: OID mana yang dibaca dan bagaimana nilainya ditafsirkan.
 *
 * Perbedaan antar vendor GPON hampir seluruhnya bersifat data, bukan alur —
 * langkahnya sama (walk tabel ONU, ubah satuan, petakan status), yang berbeda
 * hanya angka OID dan skalanya. Karena itu vendor didefinisikan sebagai profil
 * data, bukan sebagai kelas baru: menambah vendor cukup menambah satu [MibProfile].
 */
data class MibProfile(
    val vendor: String,
    val serialNumberOid: String,
    val statusOid: String,
    val rxPowerOid: String,
    val txPowerOid: String?,
    val distanceOid: String?,
    val uptimeOid: String?,
    /** Pembagi untuk mengubah nilai mentah menjadi dBm. */
    val opticalPowerDivisor: Double,
    /** Nilai mentah yang berarti "tidak ada pembacaan", mis. -32768 atau 2147483647. */
    val opticalPowerSentinels: Set<Long>,
    val statusMapping: Map<String, OnuOperationalStatus>,
    /** Serial GPON umumnya 4 huruf vendor + 8 digit heksa. */
    val serialIsHex: Boolean = true,
)

/**
 * Adapter GPON berbasis SNMP yang perilakunya ditentukan [MibProfile].
 *
 * PERINGATAN: OID di [MibProfiles] disusun dari dokumentasi MIB publik dan
 * BELUM diverifikasi terhadap perangkat sungguhan. Firmware berbeda kerap
 * menggeser sub-tree. Sebelum dipakai produksi, jalankan `snmpwalk` pada OLT
 * sungguhan dan sesuaikan — itu pekerjaan Phase 2b.
 */
class GponSnmpAdapter(
    private val profile: MibProfile,
    /** Baku memakai UDP; pengujian menyuplai pembaca dengan baris tiruan. */
    private val readerFactory: SnmpReaderFactory = SnmpReaderFactory { host, port, community ->
        SnmpSession.open(host, port, community)
    },
    /** Sumber waktu, agar `observedAt` bisa dikunci di pengujian. */
    private val clock: () -> Instant = Instant::now,
) : OltAdapter {

    private val log = LoggerFactory.getLogger(javaClass)

    override val vendor: String get() = profile.vendor

    override fun probe(target: OltTarget): ProbeResult {
        val community = target.snmpCommunity
            ?: return ProbeResult.Unreachable("Community string SNMP belum diisi")
        return try {
            val startedAt = System.nanoTime()
            readerFactory.open(target.host, target.snmpPort, community).use { reader ->
                val description = reader.get(SnmpSession.SYS_DESCR)
                val elapsed = (System.nanoTime() - startedAt) / 1_000_000
                ProbeResult.Reachable(description, elapsed)
            }
        } catch (ex: Exception) {
            ProbeResult.Unreachable(ex.message ?: ex::class.simpleName ?: "gagal menghubungi perangkat")
        }
    }

    override fun pollOnus(target: OltTarget): List<OnuReading> {
        val community = target.snmpCommunity
            ?: throw OltProtocolException("Community string SNMP belum diisi untuk ${target.oltCode}")

        val columns = listOfNotNull(
            profile.serialNumberOid,
            profile.statusOid,
            profile.rxPowerOid,
            profile.txPowerOid,
            profile.distanceOid,
            profile.uptimeOid,
        )

        val observedAt = clock()
        return readerFactory.open(target.host, target.snmpPort, community).use { reader ->
            reader.walkTable(columns).mapNotNull { (index, row) ->
                toReading(target, index, row, observedAt)
            }
        }
    }

    private fun toReading(
        target: OltTarget,
        index: String,
        row: Map<String, String>,
        observedAt: Instant,
    ): OnuReading? {
        // Baris tanpa serial tidak bisa dipetakan ke pelanggan mana pun, jadi
        // dibuang di sini daripada mengotori data di server.
        val serial = row[profile.serialNumberOid]?.let(::normalizeSerial) ?: return null

        return OnuReading(
            serialNumber = serial,
            oltCode = target.oltCode,
            ponPortLabel = ponPortLabelFrom(index),
            status = profile.statusMapping[row[profile.statusOid]] ?: OnuOperationalStatus.UNKNOWN,
            rxPowerDbm = opticalPower(row[profile.rxPowerOid]),
            txPowerDbm = profile.txPowerOid?.let { opticalPower(row[it]) },
            uptimeSeconds = profile.uptimeOid?.let { row[it]?.toLongOrNull() },
            distanceMeters = profile.distanceOid?.let { row[it]?.toIntOrNull() },
            observedAt = observedAt,
        )
    }

    /**
     * Serial GPON dilaporkan sebagai oktet mentah; empat oktet pertama adalah
     * kode vendor ASCII dan sisanya heksa — mis. `ZTEG` + `C0FFEE01`. Format
     * inilah yang tercetak di stiker perangkat, sehingga cocok dengan yang
     * diinput teknisi.
     */
    private fun normalizeSerial(raw: String): String? {
        val cleaned = raw.trim().replace(":", "").replace(" ", "")
        if (cleaned.isEmpty()) return null
        if (!profile.serialIsHex) return cleaned.uppercase()

        return runCatching {
            val bytes = cleaned.chunked(2).map { it.toInt(16).toByte() }
            if (bytes.size < 8) return@runCatching cleaned.uppercase()
            val vendorCode = String(bytes.take(4).toByteArray(), Charsets.US_ASCII)
            val suffix = bytes.drop(4).joinToString("") { "%02X".format(it) }
            "$vendorCode$suffix"
        }.getOrElse { cleaned.uppercase() }
    }

    /**
     * Mengubah nilai optik mentah menjadi dBm, memperhitungkan sentinel
     * "tidak terbaca". Sentinel yang lolos akan tampak sebagai redaman -3276,8 dBm
     * dan langsung memicu alarm palsu — karena itu dibuang menjadi `null`.
     */
    private fun opticalPower(raw: String?): Double? {
        val value = raw?.trim()?.toLongOrNull() ?: return null
        if (value in profile.opticalPowerSentinels) return null
        val dbm = value / profile.opticalPowerDivisor
        if (dbm !in PLAUSIBLE_DBM_RANGE) {
            log.debug("Nilai redaman {} dBm di luar rentang masuk akal, diabaikan", dbm)
            return null
        }
        return Math.round(dbm * 100) / 100.0
    }

    /**
     * Indeks tabel ONU mengandung identitas PON port-nya. Pemetaan pastinya
     * bergantung firmware, jadi untuk sekarang indeksnya diteruskan apa adanya
     * dan server memakai penempatan ODP sebagai sumber kebenaran topologi.
     */
    private fun ponPortLabelFrom(index: String): String? = index.takeIf { it.isNotBlank() }

    private companion object {
        /** Di luar rentang ini pasti salah baca, bukan ONU yang bermasalah. */
        val PLAUSIBLE_DBM_RANGE = -50.0..10.0
    }
}

/**
 * Profil MIB per vendor.
 *
 * Nilai-nilai ini BELUM diuji terhadap perangkat sungguhan — lihat peringatan
 * di [GponSnmpAdapter].
 */
object MibProfiles {

    /** ZTE C300/C320 — redaman dilaporkan dalam satuan 0,001 dBm. */
    val ZTE = MibProfile(
        vendor = "ZTE",
        serialNumberOid = "1.3.6.1.4.1.3902.1012.3.28.1.1.5",
        statusOid = "1.3.6.1.4.1.3902.1012.3.28.2.1.4",
        rxPowerOid = "1.3.6.1.4.1.3902.1012.3.50.12.1.1.10",
        txPowerOid = "1.3.6.1.4.1.3902.1012.3.50.12.1.1.14",
        distanceOid = "1.3.6.1.4.1.3902.1012.3.11.3.1.6",
        uptimeOid = null,
        opticalPowerDivisor = 1_000.0,
        opticalPowerSentinels = setOf(2_147_483_647L, -2_147_483_648L),
        statusMapping = mapOf(
            "1" to OnuOperationalStatus.OFFLINE,
            "2" to OnuOperationalStatus.LOS,
            "3" to OnuOperationalStatus.ONLINE,
        ),
    )

    /** Huawei MA5600/MA5800 — redaman dalam satuan 0,01 dBm. */
    val HUAWEI = MibProfile(
        vendor = "HUAWEI",
        serialNumberOid = "1.3.6.1.4.1.2011.6.128.1.1.2.43.1.3",
        statusOid = "1.3.6.1.4.1.2011.6.128.1.1.2.46.1.15",
        rxPowerOid = "1.3.6.1.4.1.2011.6.128.1.1.2.51.1.4",
        txPowerOid = "1.3.6.1.4.1.2011.6.128.1.1.2.51.1.6",
        distanceOid = "1.3.6.1.4.1.2011.6.128.1.1.2.46.1.20",
        uptimeOid = null,
        opticalPowerDivisor = 100.0,
        opticalPowerSentinels = setOf(2_147_483_647L, 65_535L),
        statusMapping = mapOf(
            "1" to OnuOperationalStatus.ONLINE,
            "2" to OnuOperationalStatus.OFFLINE,
            "3" to OnuOperationalStatus.LOS,
        ),
    )

    /** Fiberhome AN5516 — redaman dalam satuan 0,01 dBm dengan offset. */
    val FIBERHOME = MibProfile(
        vendor = "FIBERHOME",
        serialNumberOid = "1.3.6.1.4.1.5875.800.3.9.3.3.1.3",
        statusOid = "1.3.6.1.4.1.5875.800.3.9.3.3.1.5",
        rxPowerOid = "1.3.6.1.4.1.5875.800.3.9.4.1.1.4",
        txPowerOid = "1.3.6.1.4.1.5875.800.3.9.4.1.1.3",
        distanceOid = null,
        uptimeOid = null,
        opticalPowerDivisor = 100.0,
        opticalPowerSentinels = setOf(2_147_483_647L, -1L),
        statusMapping = mapOf(
            "1" to OnuOperationalStatus.ONLINE,
            "2" to OnuOperationalStatus.OFFLINE,
            "3" to OnuOperationalStatus.LOS,
        ),
    )

    fun all(): List<MibProfile> = listOf(ZTE, HUAWEI, FIBERHOME)
}

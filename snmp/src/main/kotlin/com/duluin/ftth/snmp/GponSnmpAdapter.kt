package com.duluin.ftth.snmp

import com.duluin.ftth.contract.OltTarget
import com.duluin.ftth.contract.OnuDownCause
import com.duluin.ftth.contract.OnuOperationalStatus
import com.duluin.ftth.contract.OnuReading
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.Locale

/**
 * Adapter GPON berbasis SNMP yang perilakunya ditentukan [MibProfile].
 *
 * Batas bukti per profil ada di docs/gpon-profile-evidence.md. Validasi hardware
 * ditunda; konstanta yang belum terdokumentasi bukan dukungan vendor terverifikasi.
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

    /**
     * Peran tiap OID profil, memakai penafsir adapter ini sendiri — sehingga alat validasi
     * lapangan menampilkan persis nilai yang akan dipakai polling, bukan tafsiran kedua.
     * OID serial/status harus tersedia; nilai status yang hilang tetap UNKNOWN.
     */
    override val oidPlan: List<OidRole> get() = listOf(
        OidRole("SERIAL", "Serial number ONU", profile.serialNumberOid, essential = true, interpret = ::normalizeSerial),
        OidRole("STATUS", "Status ONU", profile.statusOid, essential = true) { profile.statusMapping[it]?.name },
        OidRole("RX_POWER", "Redaman terima (RX)", profile.rxPowerOid, interpret = ::dbmOrNull),
        OidRole("TX_POWER", "Daya kirim (TX)", profile.txPowerOid, interpret = ::dbmOrNull),
        OidRole("DISTANCE", "Jarak ranging", profile.distanceOid) { distanceMeters(it)?.let { meters -> "$meters m" } },
        OidRole("UPTIME", "Lama menyala", profile.uptimeOid) { it.toLongOrNull()?.let { s -> "$s dtk" } },
        OidRole("DOWN_CAUSE", "Sebab putus terakhir", profile.downCauseOid) { profile.downCauseMapping[it]?.name },
        OidRole("LAST_OFF", "Waktu putus terakhir", profile.lastOffAtOid) { timestampOf(it)?.toString() },
        OidRole("LAST_ON", "Waktu nyala terakhir", profile.lastOnAtOid) { timestampOf(it)?.toString() },
    )

    override fun pollOnus(target: OltTarget): List<OnuReading> {
        if (profile.serialNumberOid == null || profile.statusOid == null) {
            throw OltProtocolException("GPON ${profile.vendor}: documented serial/status OIDs unavailable; see docs/gpon-profile-evidence.md")
        }
        val community = target.snmpCommunity
            ?: throw OltProtocolException("Community string SNMP belum diisi untuk ${target.oltCode}")

        val columns = listOfNotNull(
            profile.serialNumberOid,
            profile.statusOid,
            profile.rxPowerOid,
            profile.txPowerOid,
            profile.distanceOid,
            profile.uptimeOid,
            profile.downCauseOid,
            profile.lastOffAtOid,
            profile.lastOnAtOid,
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
            distanceMeters = profile.distanceOid?.let { distanceMeters(row[it]) },
            observedAt = observedAt,
            lastDownCause = lastDownCause(row),
            lastOffAt = timestampAt(profile.lastOffAtOid, row),
            lastOnAt = timestampAt(profile.lastOnAtOid, row),
            pathProvenance = com.duluin.ftth.contract.OnuPathProvenance.UNVERIFIED_INDEX,
        )
    }

    /**
     * Menafsirkan register waktu OLT (last off / last on). Formatnya bergantung
     * firmware — sebagian melaporkan epoch detik, sebagian string ISO-8601 — jadi
     * keduanya dicoba dan nilai yang tak terbaca diabaikan daripada salah tafsir.
     * Selama OID/format vendor belum didokumentasikan, profil baku tidak mengisinya.
     */
    private fun timestampAt(oid: String?, row: Map<String, String>): Instant? =
        oid?.let { row[it] }?.let(::timestampOf)

    private fun timestampOf(raw: String): Instant? {
        val text = raw.trim().takeIf { it.isNotBlank() } ?: return null
        text.toLongOrNull()?.let { return Instant.ofEpochSecond(it) }
        return runCatching { Instant.parse(text) }.getOrNull()
    }

    /** Nilai optik mentah sebagai teks siap baca; `null` bila sentinel/di luar nalar. */
    private fun dbmOrNull(raw: String): String? = opticalPower(raw)?.let { "$it dBm" }

    /**
     * Menerjemahkan register "last down cause" OLT ke [OnuDownCause]. Nilai mentah
     * yang tak ada di [MibProfile.downCauseMapping] dianggap [OnuDownCause.UNKNOWN]
     * daripada dibuang — bahwa ONU pernah putus tetap informasi berharga meski
     * sebabnya belum bisa dipetakan. Selama OID vendor belum diisi, hasilnya `null`.
     */
    private fun lastDownCause(row: Map<String, String>): OnuDownCause? {
        val raw = profile.downCauseOid?.let { row[it] }?.trim()?.takeIf { it.isNotBlank() } ?: return null
        return profile.downCauseMapping[raw] ?: OnuDownCause.UNKNOWN
    }

    /**
     * Serial GPON dilaporkan sebagai oktet mentah; empat oktet pertama adalah
     * kode vendor ASCII dan sisanya heksa — mis. `ZTEG` + `C0FFEE01`. Format
     * inilah yang tercetak di stiker perangkat, sehingga cocok dengan yang
     * diinput teknisi.
     */
    private fun normalizeSerial(raw: String): String? {
        if (!profile.serialIsHex) return raw.trim().uppercase(Locale.ROOT).takeIf(PRINTED_SERIAL::matches)
        val cleaned = raw.trim().replace(":", "").replace(" ", "")
        val bytes = when {
            raw.length == 8 && raw.all { it.code in 32..126 } -> raw.toByteArray(Charsets.US_ASCII)
            HEX_SERIAL.matches(cleaned) -> cleaned.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            else -> return null
        }
        val vendorCode = String(bytes.take(4).toByteArray(), Charsets.US_ASCII)
        if (!VENDOR_CODE.matches(vendorCode)) return null
        val suffix = bytes.drop(4).joinToString("") { "%02X".format(it) }
        return "$vendorCode$suffix"
    }

    private fun distanceMeters(raw: String?): Int? = raw?.toIntOrNull()?.takeIf { it >= 0 }

    /**
     * Mengubah nilai optik mentah menjadi dBm, memperhitungkan sentinel
     * "tidak terbaca". Sentinel yang lolos akan tampak sebagai redaman -3276,8 dBm
     * dan langsung memicu alarm palsu — karena itu dibuang menjadi `null`.
     */
    private fun opticalPower(raw: String?): Double? {
        val divisor = profile.opticalPowerDivisor ?: return null
        val value = raw?.trim()?.toLongOrNull() ?: return null
        if (value in profile.opticalPowerSentinels) return null
        val dbm = value / divisor
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
        val HEX_SERIAL = Regex("[0-9A-Fa-f]{16}")
        val VENDOR_CODE = Regex("[A-Z]{4}")
        val PRINTED_SERIAL = Regex("[A-Z]{4}[0-9A-F]{8}")
        /** Di luar rentang ini pasti salah baca, bukan ONU yang bermasalah. */
        val PLAUSIBLE_DBM_RANGE = -50.0..10.0
    }
}

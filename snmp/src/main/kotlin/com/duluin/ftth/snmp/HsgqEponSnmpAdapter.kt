package com.duluin.ftth.snmp

import com.duluin.ftth.contract.OltTarget
import com.duluin.ftth.contract.OnuOperationalStatus
import com.duluin.ftth.contract.OnuReading
import org.slf4j.LoggerFactory
import java.time.Instant

/**
 * Adapter untuk OLT EPON berbasis chipset HSGQ (mis. HSGQ-E04I, enterprise
 * `.1.3.6.1.4.1.50224`) — perangkat 4/8-PON murah yang jamak dipakai ISP kecil.
 *
 * Kenapa adapter tersendiri, bukan satu [MibProfile] lagi seperti vendor GPON?
 * Karena bedanya bukan cuma angka OID, tapi **alur**:
 *
 *  1. **Identitas ONU = MAC**, bukan serial GPON (4 huruf vendor ASCII + 8 heksa).
 *     EPON tidak punya serial ONU-ID; yang unik dan tercetak di perangkat adalah MAC.
 *  2. **Dua tabel, indeks beda.** Status/MAC/nama ada di tabel inventori
 *     `…3.3.2.1.<kol>.<onuIdx>`, sementara redaman optik ada di tabel terpisah
 *     `…3.3.3.1.<kol>.<onuIdx>.0.0` (indeksnya berakhiran `.0.0`). [GponSnmpAdapter]
 *     mengasumsikan semua kolom satu ONU berada pada satu indeks — asumsi yang tak
 *     berlaku di sini — jadi adapter ini men-walk dua tabel lalu **menggabungkannya
 *     lewat indeks ONU**.
 *  3. **ONU offline absen dari tabel optik.** Modul optik hanya dilaporkan untuk ONU
 *     yang sedang menyala, jadi baris optik yang hilang berarti "tak ada pembacaan",
 *     bukan error — join-nya toleran terhadap ketiadaan itu.
 *
 * OID di sini SUDAH diverifikasi terhadap perangkat sungguhan (snmpwalk HSGQ-E04I),
 * berbeda dari profil GPON di [MibProfiles] yang masih menunggu verifikasi lapangan.
 */
class HsgqEponSnmpAdapter(
    /** Baku memakai UDP; pengujian menyuplai pembaca dengan baris tiruan. */
    private val readerFactory: SnmpReaderFactory = SnmpReaderFactory { host, port, community ->
        SnmpSession.open(host, port, community)
    },
    /** Sumber waktu, agar `observedAt` bisa dikunci di pengujian. */
    private val clock: () -> Instant = Instant::now,
) : OltAdapter {

    private val log = LoggerFactory.getLogger(javaClass)

    override val vendor: String get() = VENDOR

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

        val observedAt = clock()
        return readerFactory.open(target.host, target.snmpPort, community).use { reader ->
            // Dua walk: inventori (semua ONU, online+offline) lalu optik (hanya yang online).
            val inventory = reader.walkTable(INVENTORY_COLUMNS)
            val optical = reader.walkTable(OPTICAL_COLUMNS)
            inventory.mapNotNull { (index, row) ->
                toReading(target, index, row, optical, observedAt)
            }
        }
    }

    private fun toReading(
        target: OltTarget,
        index: String,
        row: Map<String, String>,
        optical: Map<String, Map<String, String>>,
        observedAt: Instant,
    ): OnuReading? {
        // Tanpa MAC, ONU tak bisa dipetakan ke pelanggan mana pun → buang di sini
        // daripada mengotori data server (setara dengan "baris tanpa serial" pada GPON).
        val mac = row[MAC_OID]?.let(::normalizeMac) ?: return null

        // Tabel optik diindeks <onuIdx>.0.0; ONU offline tak punya baris → optik null.
        val opticalRow = optical["$index.0.0"].orEmpty()

        return OnuReading(
            serialNumber = mac,
            oltCode = target.oltCode,
            ponPortLabel = ponPortLabelFrom(index),
            status = STATUS_MAPPING[row[STATUS_OID]] ?: OnuOperationalStatus.UNKNOWN,
            rxPowerDbm = opticalPower(opticalRow[RX_POWER_OID]),
            txPowerDbm = opticalPower(opticalRow[TX_POWER_OID]),
            // EPON HSGQ tak melaporkan uptime per-ONU; jarak ranging (`.15`) belum
            // diverifikasi satuannya terhadap panjang fiber yang diketahui → jangan
            // ditebak, biarkan null daripada menampilkan jarak yang salah.
            uptimeSeconds = null,
            distanceMeters = null,
            observedAt = observedAt,
        )
    }

    /**
     * MAC dilaporkan sebagai oktet mentah. snmp4j merendernya heksa berpemisah titik
     * dua (`c0:fd:84:65:fd:12`); net-snmp memakai spasi. Keduanya dibersihkan menjadi
     * heksa huruf besar tanpa pemisah (`C0FD8465FD12`) — bentuk yang dicocokkan server
     * ke MAC ONU pelanggan.
     */
    private fun normalizeMac(raw: String): String? {
        val cleaned = raw.trim().replace(":", "").replace(" ", "").replace("-", "")
        return cleaned.uppercase().takeIf { it.isNotEmpty() }
    }

    /**
     * Indeks ONU mengkodekan port PON di byte-nya: `0x0100_PPNN` (PP = nomor PON,
     * NN = nomor ONU). Mis. `16777473` = `0x01000101` → PON1/ONU1. Server tetap
     * memakai penempatan ODP sebagai kebenaran topologi; label ini sekadar petunjuk.
     */
    private fun ponPortLabelFrom(index: String): String? {
        val idx = index.toLongOrNull() ?: return null
        val pon = (idx shr 8) and 0xFF
        return "PON$pon"
    }

    /**
     * Mengubah nilai optik mentah (satuan 0,01 dBm) menjadi dBm, membuang sentinel
     * "tidak terbaca" dan nilai di luar rentang masuk akal agar tak memicu alarm palsu
     * — sama semangatnya dengan [GponSnmpAdapter.opticalPower].
     */
    private fun opticalPower(raw: String?): Double? {
        val value = raw?.trim()?.toLongOrNull() ?: return null
        if (value == OPTICAL_SENTINEL) return null
        val dbm = value / OPTICAL_DIVISOR
        if (dbm !in PLAUSIBLE_DBM_RANGE) {
            log.debug("Nilai redaman {} dBm di luar rentang masuk akal, diabaikan", dbm)
            return null
        }
        return Math.round(dbm * 100) / 100.0
    }

    companion object {
        const val VENDOR = "HSGQ"

        private const val BASE = "1.3.6.1.4.1.50224.3"

        /** Tabel inventori ONU `…3.3.2.1.<kol>.<onuIdx>` — memuat semua ONU. */
        const val NAME_OID = "$BASE.3.2.1.2" // STRING, mis. "ONU01/01"
        const val MAC_OID = "$BASE.3.2.1.7" // Hex-STRING, identitas ONU
        const val STATUS_OID = "$BASE.3.2.1.8" // INTEGER, 1=online 2=offline

        /** Tabel diagnostik optik `…3.3.3.1.<kol>.<onuIdx>.0.0` — hanya ONU online. */
        const val RX_POWER_OID = "$BASE.3.3.1.4" // INTEGER, RX 0,01 dBm
        const val TX_POWER_OID = "$BASE.3.3.1.5" // INTEGER, TX 0,01 dBm

        private val INVENTORY_COLUMNS = listOf(NAME_OID, MAC_OID, STATUS_OID)
        private val OPTICAL_COLUMNS = listOf(RX_POWER_OID, TX_POWER_OID)

        private val STATUS_MAPPING = mapOf(
            "1" to OnuOperationalStatus.ONLINE,
            "2" to OnuOperationalStatus.OFFLINE,
        )

        private const val OPTICAL_DIVISOR = 100.0
        private const val OPTICAL_SENTINEL = -2_147_483_648L

        /** Di luar rentang ini pasti salah baca, bukan ONU yang bermasalah. */
        private val PLAUSIBLE_DBM_RANGE = -50.0..10.0
    }
}

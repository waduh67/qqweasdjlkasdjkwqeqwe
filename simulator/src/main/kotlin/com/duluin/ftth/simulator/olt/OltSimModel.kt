package com.duluin.ftth.simulator.olt

import com.duluin.ftth.snmp.HsgqEponSnmpAdapter
import com.duluin.ftth.snmp.SnmpSession
import org.snmp4j.smi.Integer32
import org.snmp4j.smi.OID
import org.snmp4j.smi.OctetString
import org.snmp4j.smi.Variable
import java.time.Instant
import java.util.NavigableMap
import java.util.TreeMap
import kotlin.math.roundToInt
import kotlin.math.sin

/** Status ONU seperti dilaporkan register OLT HSGQ (1=online, 2=offline). */
enum class SimOnuStatus(val code: Int) { ONLINE(1), OFFLINE(2) }

/**
 * Satu ONU tiruan. Identitasnya MAC (persis EPON: [HsgqEponSnmpAdapter] memakai MAC
 * sebagai serial). [index] mengkode PON di byte-nya (`0x0100_PPNN`) — format yang
 * dibaca balik adapter untuk melabeli PON.
 */
class SimOnu(
    val pon: Int,
    val onu: Int,
    /** 6 oktet MAC; ber-OUI C0:FD:84 agar byte pertama non-printable → snmp4j merender heksa. */
    val mac: ByteArray,
    val name: String,
    val status: SimOnuStatus,
    /** Redaman RX dasar (dBm) untuk ONU online; null bila offline (absen dari tabel optik). */
    private val rxBaseDbm: Double?,
) {
    val index: Int get() = 0x01000000 or (pon shl 8) or onu

    /**
     * RX kini: dasar + ayunan harian ±0,4 dB dengan fase digeser per ONU (meniru suhu),
     * supaya alarm yang terpicu berarti sesuatu dan tak semua ONU bergerak serempak.
     * Sama semangatnya dengan `SimulatorOltAdapter` collector.
     */
    fun rxRaw(now: Instant): Int? {
        val base = rxBaseDbm ?: return null
        val daily = sin((now.epochSecond % 86_400) / 86_400.0 * 2 * Math.PI + index) * 0.4
        val dbm = Math.round((base + daily) * 100) / 100.0
        return (dbm * 100).roundToInt() // satuan 0,01 dBm sesuai tabel optik HSGQ
    }
}

/**
 * Populasi ONU sebuah OLT + perakitan snapshot OID→nilai yang di-serve agen SNMP.
 *
 * Snapshot dibangun ulang tiap request (bukan sekali) agar RX ikut bergerak seiring
 * waktu — poll berikutnya melihat nilai yang sedikit berbeda, seperti perangkat nyata.
 * OID diambil dari konstanta [HsgqEponSnmpAdapter] agar tak ada nomor yang bercabang.
 */
class OltSimModel(
    private val sysDescr: String,
    val onus: List<SimOnu>,
) {
    fun snapshot(now: Instant): NavigableMap<OID, Variable> {
        val map = TreeMap<OID, Variable>()
        map[OID(SnmpSession.SYS_DESCR)] = OctetString(sysDescr)
        for (o in onus) {
            val idx = o.index
            // Tabel inventori …3.2.1.<kol>.<idx> — semua ONU (online + offline).
            map[inv(HsgqEponSnmpAdapter.NAME_OID, idx)] = OctetString(o.name)
            map[inv(HsgqEponSnmpAdapter.MAC_OID, idx)] = OctetString(o.mac)
            map[inv(HsgqEponSnmpAdapter.STATUS_OID, idx)] = Integer32(o.status.code)
            // Tabel optik …3.3.1.<kol>.<idx>.0.0 — hanya ONU online (offline absen = tak ada bacaan).
            if (o.status == SimOnuStatus.ONLINE) {
                o.rxRaw(now)?.let { map[opt(HsgqEponSnmpAdapter.RX_POWER_OID, idx)] = Integer32(it) }
                map[opt(HsgqEponSnmpAdapter.TX_POWER_OID, idx)] = Integer32(TX_RAW)
            }
        }
        return map
    }

    private fun inv(base: String, idx: Int): OID = OID(base).append(idx)
    private fun opt(base: String, idx: Int): OID = OID(base).append(idx).append(0).append(0)

    companion object {
        /** TX konstan +2,10 dBm — cukup realistis, bukan fokus alarm. */
        private const val TX_RAW = 210

        /**
         * Bikin populasi yang punya variasi bermakna untuk melatih mesin alarm:
         * mayoritas sehat, sebagian memburuk (kandidat deteksi degradasi), satu kritis
         * (memicu LOW_RX), dan yang terakhir mati (absen dari optik) — proporsi mirip
         * jaringan nyata, bukan setengah rusak yang bikin alarm bising.
         */
        fun populate(ponCount: Int, onusPerPon: Int, macSlot: Int = 0): List<SimOnu> =
            (1..ponCount).flatMap { pon ->
                (1..onusPerPon).map { onu ->
                    // Oktet ke-4 = macSlot → serial UNIK lintas-OLT (lihat OltInstance.macSlot).
                    val mac = byteArrayOf(
                        0xC0.toByte(), 0xFD.toByte(), 0x84.toByte(), macSlot.toByte(), pon.toByte(), onu.toByte(),
                    )
                    val (status, rxBase) = profile(onu, onusPerPon)
                    SimOnu(pon, onu, mac, "ONU%02d/%02d".format(pon, onu), status, rxBase)
                }
            }

        private fun profile(onu: Int, perPon: Int): Pair<SimOnuStatus, Double?> = when {
            onu == perPon -> SimOnuStatus.OFFLINE to null       // mati (mis. dying gasp / fiber putus)
            onu == perPon - 1 -> SimOnuStatus.ONLINE to -28.4    // kritis → alarm LOW_RX
            onu % 4 == 0 -> SimOnuStatus.ONLINE to -25.6         // memburuk → kandidat degradasi
            else -> SimOnuStatus.ONLINE to -21.0                 // sehat
        }
    }
}

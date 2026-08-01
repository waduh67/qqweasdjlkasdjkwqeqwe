package com.duluin.ftth.snmp

import org.snmp4j.CommunityTarget
import org.snmp4j.PDU
import org.snmp4j.Snmp
import org.snmp4j.mp.SnmpConstants
import org.snmp4j.smi.OID
import org.snmp4j.smi.OctetString
import org.snmp4j.smi.UdpAddress
import org.snmp4j.smi.VariableBinding
import org.snmp4j.transport.DefaultUdpTransportMapping
import org.snmp4j.util.DefaultPDUFactory
import org.snmp4j.util.TableUtils
import java.time.Duration

/**
 * Pembungkus tipis SNMPv2c di atas snmp4j.
 *
 * Ada dua alasan lapisan ini dibuat alih-alih memakai snmp4j langsung dari
 * adapter vendor. Pertama, pengelolaan sumber daya: `Snmp` membuka soket UDP
 * yang wajib ditutup, dan [use] membuatnya sulit dilupakan. Kedua, GETBULK —
 * membaca ribuan ONU satu OID per request akan memakan waktu berjam-jam;
 * [walkTable] mengambilnya per baris.
 */
class SnmpSession private constructor(
    private val snmp: Snmp,
    private val target: CommunityTarget<UdpAddress>,
) : SnmpReader {

    /** Mengambil satu nilai skalar. `null` bila OID tidak ada di perangkat. */
    override fun get(oid: String): String? {
        val pdu = PDU().apply {
            type = PDU.GET
            add(VariableBinding(OID(oid)))
        }
        val response = snmp.send(pdu, target).response
            ?: throw OltProtocolException("Tidak ada jawaban SNMP untuk OID $oid")
        val binding = response.get(0) ?: return null
        if (binding.isException) return null
        return binding.variable.toString()
    }

    /**
     * Membaca beberapa kolom tabel sekaligus dan mengembalikannya per baris,
     * dikunci indeks baris (untuk tabel ONU, indeksnya adalah ONU ID).
     *
     * Kolom disatukan dalam satu walk supaya nilai-nilai satu ONU berasal dari
     * saat yang kurang lebih sama — kalau tiap kolom di-walk terpisah, status dan
     * redaman sebuah ONU bisa terpaut puluhan detik dan tampak tidak konsisten.
     */
    override fun walkTable(columnOids: List<String>): Map<String, Map<String, String>> {
        val utils = TableUtils(snmp, DefaultPDUFactory(PDU.GETBULK))
        utils.maxNumColumnsPerPDU = columnOids.size
        val events = utils.getTable(target, columnOids.map { OID(it) }.toTypedArray(), null, null)

        val rows = LinkedHashMap<String, MutableMap<String, String>>()
        for (event in events) {
            if (event.isError) throw OltProtocolException("Walk SNMP gagal: ${event.errorMessage}")
            val index = event.index?.toString() ?: continue
            val row = rows.getOrPut(index) { LinkedHashMap() }
            event.columns?.forEachIndexed { position, binding ->
                if (binding != null && !binding.isException) {
                    row[columnOids[position]] = binding.variable.toString()
                }
            }
        }
        return rows
    }

    override fun close() = snmp.close()

    companion object {
        /** sysDescr.0 — dipakai untuk memastikan perangkat menjawab. */
        const val SYS_DESCR = "1.3.6.1.2.1.1.1.0"

        fun open(
            host: String,
            port: Int,
            community: String,
            timeout: Duration = Duration.ofSeconds(3),
            retries: Int = 2,
        ): SnmpSession {
            val transport = DefaultUdpTransportMapping()
            val snmp = Snmp(transport)
            transport.listen()
            val target = CommunityTarget<UdpAddress>().apply {
                this.community = OctetString(community)
                this.address = UdpAddress("$host/$port")
                this.version = SnmpConstants.version2c
                this.timeout = timeout.toMillis()
                this.retries = retries
            }
            return SnmpSession(snmp, target)
        }
    }
}

package com.duluin.ftth.simulator.olt

import org.slf4j.LoggerFactory
import org.snmp4j.CommandResponder
import org.snmp4j.CommandResponderEvent
import org.snmp4j.MessageDispatcher
import org.snmp4j.MessageDispatcherImpl
import org.snmp4j.PDU
import org.snmp4j.Snmp
import org.snmp4j.mp.MPv1
import org.snmp4j.mp.MPv2c
import org.snmp4j.mp.StatusInformation
import org.snmp4j.smi.Address
import org.snmp4j.smi.Null
import org.snmp4j.smi.OID
import org.snmp4j.smi.UdpAddress
import org.snmp4j.smi.Variable
import org.snmp4j.smi.VariableBinding
import org.snmp4j.transport.DefaultUdpTransportMapping
import java.net.InetAddress
import java.util.NavigableMap

/**
 * Agen SNMPv2c minimalis yang menyajikan satu snapshot OID→nilai.
 *
 * Sengaja ditulis tangan di atas inti snmp4j (bukan snmp4j-agent) supaya lab tetap
 * ramping: kita cuma butuh menjawab GET/GETNEXT/GETBULK atas MIB read-only kecil —
 * tak perlu VACM/USM/boot-counter yang dibawa framework agen penuh.
 *
 * [snapshotSupplier] dipanggil **tiap request** sehingga nilai yang berubah seiring
 * waktu (mis. RX optik) ikut bergerak antar-poll, seperti perangkat sungguhan.
 *
 * GETBULK diimplementasi sesuai RFC 3416: non-repeaters diperlakukan sebagai GETNEXT
 * tunggal, repeaters di-walk `max-repetitions` langkah secara round-major, dan kolom
 * yang habis diberi `endOfMibView` — cukup untuk membuat `TableUtils` (dipakai
 * [com.duluin.ftth.snmp.SnmpSession.walkTable]) berhenti dengan benar.
 */
class HsgqOltSnmpAgent(
    private val bindAddress: String,
    private val port: Int,
    private val community: String,
    private val snapshotSupplier: () -> NavigableMap<OID, Variable>,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private var snmp: Snmp? = null
    private var dispatcher: MessageDispatcher? = null

    fun start() {
        val listen = UdpAddress(InetAddress.getByName(bindAddress), port)
        val transport = DefaultUdpTransportMapping(listen)
        val disp = MessageDispatcherImpl().apply {
            addMessageProcessingModel(MPv1())
            addMessageProcessingModel(MPv2c())
        }
        val s = Snmp(disp, transport)
        s.addCommandResponder(Responder())
        s.listen()
        dispatcher = disp
        snmp = s
        log.info("Agen SNMP OLT mendengarkan di {}/{} community='{}'", bindAddress, port, community)
    }

    fun stop() {
        try {
            snmp?.close()
        } catch (e: Exception) {
            log.warn("Gagal menutup agen SNMP", e)
        } finally {
            snmp = null
            dispatcher = null
        }
    }

    private inner class Responder : CommandResponder {
        override fun <A : Address> processPdu(event: CommandResponderEvent<A>) {
            val request = event.pdu ?: return
            // Untuk v2c, securityName = community string. Tolak diam-diam yang tak cocok.
            val requestCommunity = String(event.securityName ?: ByteArray(0))
            if (requestCommunity != community) {
                log.debug("Tolak request community '{}' (harusnya '{}')", requestCommunity, community)
                return
            }

            val snapshot = snapshotSupplier()
            val response = when (request.type) {
                PDU.GET -> handleGet(request, snapshot)
                PDU.GETNEXT -> handleNext(request, snapshot)
                PDU.GETBULK -> handleBulk(request, snapshot)
                else -> {
                    log.debug("Tipe PDU {} tak didukung", request.type)
                    return
                }
            }
            response.type = PDU.RESPONSE
            response.requestID = request.requestID

            val d = dispatcher ?: return
            try {
                d.returnResponsePdu(
                    event.messageProcessingModel,
                    event.securityModel,
                    event.securityName,
                    event.securityLevel,
                    response,
                    event.maxSizeResponsePDU,
                    event.stateReference,
                    StatusInformation(),
                )
                event.setProcessed(true)
            } catch (e: Exception) {
                log.warn("Gagal mengirim response PDU", e)
            }
        }
    }

    private fun handleGet(request: PDU, snapshot: NavigableMap<OID, Variable>): PDU {
        val response = PDU()
        for (vb in request.variableBindings) {
            val value = snapshot[vb.oid]
            response.add(
                if (value != null) VariableBinding(vb.oid, value)
                else VariableBinding(vb.oid, Null.noSuchInstance),
            )
        }
        return response
    }

    private fun handleNext(request: PDU, snapshot: NavigableMap<OID, Variable>): PDU {
        val response = PDU()
        for (vb in request.variableBindings) {
            response.add(nextBinding(vb.oid, snapshot))
        }
        return response
    }

    private fun handleBulk(request: PDU, snapshot: NavigableMap<OID, Variable>): PDU {
        val response = PDU()
        val bindings = request.variableBindings
        val nonRepeaters = request.nonRepeaters.coerceIn(0, bindings.size)
        val maxRepetitions = request.maxRepetitions.coerceAtLeast(0)

        // Non-repeaters: satu GETNEXT masing-masing.
        for (i in 0 until nonRepeaters) {
            response.add(nextBinding(bindings[i].oid, snapshot))
        }
        // Repeaters: walk max-repetitions langkah, round-major (satu binding per kolom
        // tiap putaran). Grid HARUS persegi: TableUtils (sisi pembaca) memetakan binding
        // ke kolom BERDASARKAN POSISI dalam putaran — jadi begitu satu repeater habis, ia
        // wajib tetap diisi endOfMibView tiap putaran berikutnya (RFC 3416), bukan di-skip.
        // Melompati satu slot akan menggeser semua kolom sesudahnya dan memicu galat
        // "Agent did not return variable bindings in lexicographic order".
        val cursors = (nonRepeaters until bindings.size).map { bindings[it].oid }.toMutableList()
        val exhausted = BooleanArray(cursors.size)
        for (round in 0 until maxRepetitions) {
            for (c in cursors.indices) {
                if (exhausted[c]) {
                    response.add(VariableBinding(cursors[c], Null.endOfMibView))
                    continue
                }
                val entry = snapshot.higherEntry(cursors[c])
                if (entry == null) {
                    response.add(VariableBinding(cursors[c], Null.endOfMibView))
                    exhausted[c] = true
                } else {
                    response.add(VariableBinding(entry.key, entry.value))
                    cursors[c] = entry.key
                }
            }
        }
        return response
    }

    private fun nextBinding(oid: OID, snapshot: NavigableMap<OID, Variable>): VariableBinding {
        val entry = snapshot.higherEntry(oid) ?: return VariableBinding(oid, Null.endOfMibView)
        return VariableBinding(entry.key, entry.value)
    }
}

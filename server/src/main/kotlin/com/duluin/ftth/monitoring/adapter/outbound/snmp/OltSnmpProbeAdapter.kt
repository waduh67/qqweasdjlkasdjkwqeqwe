package com.duluin.ftth.monitoring.adapter.outbound.snmp

import com.duluin.ftth.monitoring.application.port.outbound.OltSnmpProbePort
import com.duluin.ftth.monitoring.application.port.outbound.SnmpGreeting
import com.duluin.ftth.monitoring.application.port.outbound.SnmpProbeFailure
import com.duluin.ftth.monitoring.application.port.outbound.SnmpProbeTarget
import com.duluin.ftth.monitoring.application.port.outbound.SnmpSample
import com.duluin.ftth.snmp.SnmpReaderFactory
import com.duluin.ftth.snmp.SnmpSession
import org.springframework.stereotype.Component

/**
 * Implementasi [OltSnmpProbePort] di atas SNMPv2c/UDP modul `:snmp`.
 *
 * Sengaja memakai [SnmpReaderFactory] yang sama dengan adapter polling — bukan klien SNMP
 * kedua — supaya apa yang dilihat alat validasi persis apa yang dilihat poller: timeout,
 * retry, dan cara snmp4j merender oktet mentah semuanya sama. Alat diagnostik yang memakai
 * jalur berbeda dari jalur produksi justru berbahaya: ia bisa "hijau" sementara polling tetap
 * gagal.
 */
@Component
class OltSnmpProbeAdapter(
    private val readerFactory: SnmpReaderFactory,
) : OltSnmpProbePort {

    override fun greet(target: SnmpProbeTarget): SnmpGreeting = try {
        val startedAt = System.nanoTime()
        readerFactory.open(target.host, target.port, target.community).use { reader ->
            val description = reader.get(SnmpSession.SYS_DESCR)
            SnmpGreeting(description, (System.nanoTime() - startedAt) / 1_000_000)
        }
    } catch (ex: Exception) {
        throw SnmpProbeFailure(ex.message ?: ex::class.simpleName ?: "gagal menghubungi perangkat", ex)
    }

    override fun walk(target: SnmpProbeTarget, rootOids: List<String>): Map<String, List<SnmpSample>> {
        if (rootOids.isEmpty()) return emptyMap()
        val rows = try {
            readerFactory.open(target.host, target.port, target.community).use { it.walkTable(rootOids) }
        } catch (ex: Exception) {
            throw SnmpProbeFailure(ex.message ?: ex::class.simpleName ?: "walk SNMP gagal", ex)
        }

        // `walkTable` menyusun hasil per-BARIS (indeks → kolom), sedangkan diagnostik
        // bertanya per-OID ("sub-tree ini menjawab atau tidak"), jadi hasilnya diputar.
        // OID tanpa satu pun nilai tetap muncul sebagai daftar kosong — itu justru
        // temuannya, bukan ketiadaan data.
        val byOid = rootOids.associateWith { mutableListOf<SnmpSample>() }
        rows.forEach { (index, row) ->
            row.forEach { (oid, value) -> byOid[oid]?.add(SnmpSample(index, value)) }
        }
        return byOid
    }
}

package com.duluin.ftth.monitoring.application.service

import com.duluin.ftth.common.domain.error.NotFoundException
import com.duluin.ftth.common.domain.error.ValidationException
import com.duluin.ftth.monitoring.application.port.inbound.OidCheck
import com.duluin.ftth.monitoring.application.port.inbound.OidSampleView
import com.duluin.ftth.monitoring.application.port.inbound.OidVerdict
import com.duluin.ftth.monitoring.application.port.inbound.OltSnmpCheck
import com.duluin.ftth.monitoring.application.port.inbound.OltSnmpWalk
import com.duluin.ftth.monitoring.application.port.inbound.SnmpDiagnosticUseCase
import com.duluin.ftth.monitoring.application.port.inbound.SnmpWalkRow
import com.duluin.ftth.monitoring.application.port.outbound.OltSnmpProbePort
import com.duluin.ftth.monitoring.application.port.outbound.SnmpProbeFailure
import com.duluin.ftth.monitoring.application.port.outbound.SnmpProbeTarget
import com.duluin.ftth.monitoring.application.port.outbound.SnmpSample
import com.duluin.ftth.network.NetworkApi
import com.duluin.ftth.network.OltPollingTarget
import com.duluin.ftth.snmp.AdapterRegistry
import com.duluin.ftth.snmp.OidRole
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.UUID

/**
 * Menghadapkan peta OID kami ke perangkat sungguhan dan melaporkan siapa yang bohong.
 *
 * Semua penilaian ada di sini, bukan di adapter maupun controller, supaya bisa diuji
 * tanpa perangkat: yang disuntik hanyalah [OltSnmpProbePort]. Yang dinilai adalah
 * [com.duluin.ftth.snmp.OltAdapter.oidPlan] milik adapter vendor itu sendiri — bukan
 * daftar OID yang disalin ulang di sini — sehingga hasil diagnosa selalu berbicara
 * tentang OID yang PERSIS dipakai polling.
 */
@Service
class SnmpDiagnosticService(
    private val networkApi: NetworkApi,
    private val adapterRegistry: AdapterRegistry,
    private val probe: OltSnmpProbePort,
) : SnmpDiagnosticUseCase {

    override fun checkOidPlan(oltId: UUID): OltSnmpCheck {
        val olt = resolveOlt(oltId)
        val target = olt.toProbeTarget()
        val adapter = adapterRegistry.forVendor(olt.vendor)

        // Sapa duluan, bahkan untuk vendor yang belum punya adapter: "perangkatnya hidup
        // tapi kami belum punya profil MIB-nya" adalah jawaban yang berguna — itu bahan
        // untuk menulis profil baru, bukan alasan berhenti.
        val greeting = try {
            probe.greet(target)
        } catch (ex: SnmpProbeFailure) {
            return olt.check(
                supported = adapter != null,
                reachable = false,
                systemDescription = null,
                roundTripMillis = null,
                failureReason = ex.message,
                oids = emptyList(),
            )
        }

        if (adapter == null) {
            return olt.check(
                supported = false,
                reachable = true,
                systemDescription = greeting.systemDescription,
                roundTripMillis = greeting.roundTripMillis,
                failureReason = "Vendor ${olt.vendor} belum punya adapter SNMP, jadi tak ada OID untuk diuji",
                oids = emptyList(),
            )
        }

        val plan = adapter.oidPlan
        val oids = plan.mapNotNull { it.oid }.distinct()
        // Sekali walk untuk seluruh peran: selain lebih cepat, nilainya berasal dari saat
        // yang kurang lebih sama — persis seperti polling, sehingga yang terlihat di sini
        // memang yang akan terlihat poller.
        val samples = try {
            probe.walk(target, oids)
        } catch (ex: SnmpProbeFailure) {
            return olt.check(
                supported = true,
                reachable = true,
                systemDescription = greeting.systemDescription,
                roundTripMillis = greeting.roundTripMillis,
                failureReason = "Perangkat menyapa balik tapi walk-nya gagal: ${ex.message}",
                oids = emptyList(),
            )
        }

        return olt.check(
            supported = true,
            reachable = true,
            systemDescription = greeting.systemDescription,
            roundTripMillis = greeting.roundTripMillis,
            failureReason = null,
            oids = plan.map { role -> role.judge(olt.vendor, role.oid?.let { samples[it] }.orEmpty()) },
        )
    }

    override fun walk(oltId: UUID, rootOid: String, limit: Int): OltSnmpWalk {
        val root = sanitizeRootOid(rootOid)
        val capped = limit.coerceIn(1, MAX_WALK_ROWS)
        val olt = resolveOlt(oltId)

        val startedAt = System.nanoTime()
        val samples = try {
            probe.walk(olt.toProbeTarget(), listOf(root))[root].orEmpty()
        } catch (ex: SnmpProbeFailure) {
            throw ValidationException("Walk OID $root pada OLT ${olt.code} gagal: ${ex.message}")
        }
        val elapsed = (System.nanoTime() - startedAt) / 1_000_000

        return OltSnmpWalk(
            oltId = olt.id,
            oltCode = olt.code,
            rootOid = root,
            sampleCount = samples.size,
            truncated = samples.size > capped,
            elapsedMillis = elapsed,
            // Indeks digabung kembali menjadi OID penuh supaya bisa disalin langsung ke
            // profil MIB tanpa operator merangkainya sendiri (dan salah ketik).
            rows = samples.take(capped).map { SnmpWalkRow("$root.${it.index}", it.value) },
        )
    }

    /**
     * Menilai satu peran: yang dicari bukan sekadar "menjawab atau tidak", tapi apakah
     * jawabannya bisa DIBACA aturan vendor. OID yang menjawab dengan nilai tak tertafsir
     * adalah kegagalan paling licin — polling akan tampak sukses sambil mengisi metrik
     * dengan kosong.
     */
    private fun OidRole.judge(vendor: String, samples: List<SnmpSample>): OidCheck {
        val readable = samples.map { it to interpret(it.value) }
        val unreadable = readable.count { it.second == null }
        val verdict = when {
            oid == null -> OidVerdict.NOT_CONFIGURED
            samples.isEmpty() -> OidVerdict.EMPTY
            unreadable == samples.size -> OidVerdict.UNREADABLE
            else -> OidVerdict.OK
        }
        return OidCheck(
            role = role,
            label = label,
            oid = oid,
            essential = essential,
            verdict = verdict,
            sampleCount = samples.size,
            samples = readable.take(MAX_SAMPLES_PER_ROLE).map { (sample, interpreted) ->
                OidSampleView(index = sample.index, raw = sample.value, interpreted = interpreted)
            },
            hint = hintFor(verdict, vendor, unreadable, samples.size),
        )
    }

    private fun OidRole.hintFor(verdict: OidVerdict, vendor: String, unreadable: Int, total: Int): String? =
        when (verdict) {
            OidVerdict.NOT_CONFIGURED ->
                if (essential) "OID wajib ini belum ada di profil $vendor — polling tak akan menghasilkan satu baris pun."
                else "Belum dipetakan untuk $vendor, jadi metrik ini akan selalu kosong."

            OidVerdict.EMPTY ->
                if (essential) "Perangkat tak menjawab sub-tree ini. Untuk firmware ini OID-nya besar kemungkinan berbeda — pakai walk manual untuk menemukan yang benar."
                else "Sub-tree kosong: fiturnya mungkin mati di perangkat ini, atau OID-nya berbeda di firmware ini."

            OidVerdict.UNREADABLE ->
                "Perangkat menjawab, tapi tak satu pun nilainya cocok dengan aturan $vendor — biasanya skala/satuan atau pemetaan status yang berbeda."

            OidVerdict.OK ->
                if (unreadable > 0) "$unreadable dari $total nilai tak terbaca. Lumrah untuk ONU yang sedang mati (nilai sentinel); curigai skalanya bila ONU-nya jelas menyala."
                else null
        }

    private fun resolveOlt(oltId: UUID): OltPollingTarget {
        val olt = networkApi.findPollingTargets(setOf(oltId)).firstOrNull()
            ?: throw NotFoundException("OLT tidak ditemukan")
        if (!olt.pollable) throw ValidationException("OLT ${olt.code} belum punya alamat manajemen, tak ada yang bisa dihubungi")
        if (olt.snmpCommunity.isNullOrBlank()) throw ValidationException("OLT ${olt.code} belum punya community string SNMP")
        return olt
    }

    private fun OltPollingTarget.toProbeTarget() = SnmpProbeTarget(
        // Aman: [resolveOlt] sudah menolak OLT tanpa host maupun community.
        host = host!!,
        port = snmpPort,
        community = snmpCommunity!!,
    )

    private fun OltPollingTarget.check(
        supported: Boolean,
        reachable: Boolean,
        systemDescription: String?,
        roundTripMillis: Long?,
        failureReason: String?,
        oids: List<OidCheck>,
    ) = OltSnmpCheck(
        oltId = id,
        oltCode = code,
        vendor = vendor,
        supported = supported,
        reachable = reachable,
        systemDescription = systemDescription,
        roundTripMillis = roundTripMillis,
        failureReason = failureReason,
        checkedAt = Instant.now(),
        oids = oids,
    )

    /**
     * Menjaga walk manual tetap berupa alat bedah, bukan pemindai.
     *
     * Walk dari akar (`1.3.6.1`) pada OLT berisi ribuan ONU bisa berjalan
     * belasan menit sambil membebani CPU manajemen perangkat — di jaringan produksi,
     * pelanggan yang membayar. Karena itu OID wajib berada di bawah `internet` dan cukup
     * spesifik (minimal [MIN_OID_ARCS] angka), sehingga yang bisa diminta hanyalah satu
     * sub-tree yang sudah disasar.
     */
    private fun sanitizeRootOid(raw: String): String {
        val oid = raw.trim().removePrefix(".")
        if (!OID_PATTERN.matches(oid)) throw ValidationException("OID harus berupa angka berpemisah titik, mis. 1.3.6.1.2.1.1.1")
        if (!oid.startsWith("$INTERNET_ROOT.")) throw ValidationException("OID harus berada di bawah $INTERNET_ROOT")
        if (oid.count { it == '.' } + 1 < MIN_OID_ARCS) {
            throw ValidationException("OID terlalu umum — sebutkan minimal $MIN_OID_ARCS angka agar walk tak menyapu seluruh perangkat")
        }
        return oid
    }

    private companion object {
        val OID_PATTERN = Regex("""\d+(\.\d+)+""")
        const val INTERNET_ROOT = "1.3.6.1"
        const val MIN_OID_ARCS = 7
        const val MAX_WALK_ROWS = 500
        /** Cukup untuk melihat pola nilai; lebih dari ini hanya membanjiri layar. */
        const val MAX_SAMPLES_PER_ROLE = 3
    }
}

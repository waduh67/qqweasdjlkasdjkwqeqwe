package com.duluin.ftth.monitoring

import com.duluin.ftth.common.domain.UuidV7
import com.duluin.ftth.common.domain.error.NotFoundException
import com.duluin.ftth.common.domain.error.ValidationException
import com.duluin.ftth.common.domain.geo.Coordinate
import com.duluin.ftth.contract.OltTarget
import com.duluin.ftth.monitoring.application.port.inbound.OidVerdict
import com.duluin.ftth.monitoring.application.port.outbound.OltSnmpProbePort
import com.duluin.ftth.monitoring.application.port.outbound.SnmpGreeting
import com.duluin.ftth.monitoring.application.port.outbound.SnmpProbeFailure
import com.duluin.ftth.monitoring.application.port.outbound.SnmpProbeTarget
import com.duluin.ftth.monitoring.application.port.outbound.SnmpSample
import com.duluin.ftth.monitoring.application.service.SnmpDiagnosticService
import com.duluin.ftth.network.CableCutImpact
import com.duluin.ftth.network.CablePath
import com.duluin.ftth.network.DownstreamIds
import com.duluin.ftth.network.NetworkApi
import com.duluin.ftth.network.OdcRef
import com.duluin.ftth.network.OdpRef
import com.duluin.ftth.network.OltPollingTarget
import com.duluin.ftth.network.OltRef
import com.duluin.ftth.network.PonPortTopology
import com.duluin.ftth.network.SiteRef
import com.duluin.ftth.network.UpstreamPath
import com.duluin.ftth.snmp.AdapterRegistry
import com.duluin.ftth.snmp.OidRole
import com.duluin.ftth.snmp.OltAdapter
import com.duluin.ftth.snmp.ProbeResult
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Alat validasi OID hidup atau mati dari ketajaman vonisnya, jadi itulah yang diuji:
 * membedakan "OID-nya salah" (sub-tree kosong) dari "nilainya tak terbaca aturan kami"
 * (menjawab tapi tak satu pun bisa ditafsirkan) — kegagalan kedua inilah yang selama ini
 * lolos, karena polling tetap tampak sukses sambil mengisi metrik dengan kosong.
 *
 * Semua tanpa perangkat maupun soket: [OltSnmpProbePort] dipalsukan, dan adapter vendornya
 * adalah adapter tiruan dengan peta OID yang dirancang memancing keempat vonis sekaligus.
 */
class SnmpDiagnosticServiceTest {

    private val oltId = UuidV7.generate()

    @Test
    fun `menilai tiap peran OID — terbaca, tak terbaca, kosong, dan belum dipetakan`() {
        val probe = FakeProbe(
            samples = mapOf(
                // Serial terbaca semua.
                "1.3.6.1.4.1.1.1" to listOf(SnmpSample("1", "ZTEG001"), SnmpSample("2", "ZTEG002")),
                // Menjawab, tapi skalanya asing bagi aturan kami → nilai tak tertafsir.
                "1.3.6.1.4.1.1.2" to listOf(SnmpSample("1", "-2350000"), SnmpSample("2", "abc")),
                // Sub-tree kosong: OID-nya salah untuk firmware ini.
                "1.3.6.1.4.1.1.3" to emptyList(),
            ),
        )
        val service = service(probe)

        val check = service.checkOidPlan(oltId)

        assertThat(check.oltCode).isEqualTo("OLT-01")
        assertThat(check.supported).isTrue()
        assertThat(check.reachable).isTrue()
        assertThat(check.systemDescription).isEqualTo("Fake OLT v1")
        assertThat(check.failureReason).isNull()
        // Semua OID peta ditanya dalam SATU walk, sama seperti polling.
        assertThat(probe.walkCalls).hasSize(1)

        val byRole = check.oids.associateBy { it.role }
        assertThat(byRole.keys).containsExactlyInAnyOrder("SERIAL", "RX_POWER", "STATUS", "UPTIME")

        val serial = byRole.getValue("SERIAL")
        assertThat(serial.verdict).isEqualTo(OidVerdict.OK)
        assertThat(serial.sampleCount).isEqualTo(2)
        assertThat(serial.samples.map { it.interpreted }).containsExactly("ZTEG001", "ZTEG002")
        assertThat(serial.hint).isNull()

        val rx = byRole.getValue("RX_POWER")
        assertThat(rx.verdict).isEqualTo(OidVerdict.UNREADABLE)
        assertThat(rx.sampleCount).isEqualTo(2)
        // Nilai mentahnya tetap ditampilkan — dari situlah operator menebak skalanya.
        assertThat(rx.samples.map { it.raw }).containsExactly("-2350000", "abc")
        assertThat(rx.samples.map { it.interpreted }).containsOnlyNulls()
        assertThat(rx.hint).contains("tak satu pun")

        val status = byRole.getValue("STATUS")
        assertThat(status.verdict).isEqualTo(OidVerdict.EMPTY)
        assertThat(status.sampleCount).isZero()
        assertThat(status.hint).contains("walk manual")

        val uptime = byRole.getValue("UPTIME")
        assertThat(uptime.verdict).isEqualTo(OidVerdict.NOT_CONFIGURED)
        assertThat(uptime.oid).isNull()
        assertThat(uptime.samples).isEmpty()
    }

    @Test
    fun `contoh nilai dibatasi tapi jumlah sebenarnya tetap dilaporkan`() {
        val many = (1..10).map { SnmpSample("$it", "ZTEG00$it") }
        val service = service(FakeProbe(samples = mapOf("1.3.6.1.4.1.1.1" to many)))

        val serial = service.checkOidPlan(oltId).oids.single { it.role == "SERIAL" }

        assertThat(serial.sampleCount).isEqualTo(10)
        assertThat(serial.samples).hasSize(3)
    }

    @Test
    fun `sebagian nilai tak terbaca tetap OK tapi diberi peringatan`() {
        val service = service(
            FakeProbe(
                samples = mapOf(
                    "1.3.6.1.4.1.1.2" to listOf(SnmpSample("1", "-2350"), SnmpSample("2", "rusak")),
                ),
            ),
        )

        val rx = service.checkOidPlan(oltId).oids.single { it.role == "RX_POWER" }

        assertThat(rx.verdict).isEqualTo(OidVerdict.OK)
        assertThat(rx.hint).contains("1 dari 2")
    }

    @Test
    fun `perangkat bisu dilaporkan tak terjangkau tanpa menilai OID apa pun`() {
        val probe = FakeProbe(greetFailure = "timeout setelah 3 detik")
        val service = service(probe)

        val check = service.checkOidPlan(oltId)

        assertThat(check.reachable).isFalse()
        assertThat(check.failureReason).isEqualTo("timeout setelah 3 detik")
        // Menilai OID pada perangkat yang tak menyapa balik hanya menghasilkan merah palsu.
        assertThat(check.oids).isEmpty()
        assertThat(probe.walkCalls).isEmpty()
    }

    @Test
    fun `vendor tanpa adapter dilaporkan belum didukung meski perangkatnya menjawab`() {
        val service = service(FakeProbe(), vendor = "VENDOR-ASING")

        val check = service.checkOidPlan(oltId)

        // Perangkatnya hidup — itu justru bahan untuk menulis profil MIB baru.
        assertThat(check.reachable).isTrue()
        assertThat(check.supported).isFalse()
        assertThat(check.failureReason).contains("belum punya adapter")
        assertThat(check.oids).isEmpty()
    }

    @Test
    fun `walk manual menggabungkan indeks jadi OID penuh dan menandai pemotongan`() {
        val rows = (1..5).map { SnmpSample("$it", "nilai$it") }
        val service = service(FakeProbe(samples = mapOf("1.3.6.1.4.1.50224.3.3.2.1.7" to rows)))

        val walk = service.walk(oltId, " .1.3.6.1.4.1.50224.3.3.2.1.7 ", limit = 3)

        assertThat(walk.rootOid).isEqualTo("1.3.6.1.4.1.50224.3.3.2.1.7")
        assertThat(walk.sampleCount).isEqualTo(5)
        assertThat(walk.truncated).isTrue()
        assertThat(walk.rows.map { it.oid }).containsExactly(
            "1.3.6.1.4.1.50224.3.3.2.1.7.1",
            "1.3.6.1.4.1.50224.3.3.2.1.7.2",
            "1.3.6.1.4.1.50224.3.3.2.1.7.3",
        )
        assertThat(walk.rows.first().value).isEqualTo("nilai1")
    }

    @Test
    fun `walk manual menolak OID yang menyapu seluruh perangkat atau bukan OID`() {
        val service = service(FakeProbe())

        // Terlalu umum: walk dari sini bisa berjalan belasan menit di OLT produksi.
        assertThatThrownBy { service.walk(oltId, "1.3.6.1.2.1", 50) }
            .isInstanceOf(ValidationException::class.java)
            .hasMessageContaining("terlalu umum")

        // Di luar sub-tree `internet`.
        assertThatThrownBy { service.walk(oltId, "2.3.6.1.4.1.1.1", 50) }
            .isInstanceOf(ValidationException::class.java)
            .hasMessageContaining("di bawah 1.3.6.1")

        assertThatThrownBy { service.walk(oltId, "sysDescr", 50) }
            .isInstanceOf(ValidationException::class.java)
            .hasMessageContaining("angka berpemisah titik")
    }

    @Test
    fun `OLT tanpa community atau tanpa alamat ditolak sebelum menyentuh jaringan`() {
        val probe = FakeProbe()

        assertThatThrownBy { service(probe, community = null).checkOidPlan(oltId) }
            .isInstanceOf(ValidationException::class.java)
            .hasMessageContaining("community string")

        assertThatThrownBy { service(probe, host = null).checkOidPlan(oltId) }
            .isInstanceOf(ValidationException::class.java)
            .hasMessageContaining("alamat manajemen")

        assertThatThrownBy { service(probe, olt = null).checkOidPlan(oltId) }
            .isInstanceOf(NotFoundException::class.java)

        assertThat(probe.walkCalls).isEmpty()
    }

    // --- perkakas -----------------------------------------------------------

    private fun service(
        probe: OltSnmpProbePort,
        vendor: String = FakeAdapter.VENDOR,
        host: String? = "10.10.0.1",
        community: String? = "public",
        olt: OltPollingTarget? = OltPollingTarget(oltId, "OLT-01", vendor, host, community, snmpPort = 1161),
    ) = SnmpDiagnosticService(
        networkApi = StubNetworkApi(olt),
        adapterRegistry = AdapterRegistry(listOf(FakeAdapter())),
        probe = probe,
    )

    /**
     * Adapter tiruan dengan peta OID yang sengaja timpang: satu peran tanpa OID sama
     * sekali, dan satu peran dengan penafsir ketat (hanya menerima 0,01 dBm dalam rentang
     * masuk akal) — meniru persis bentuk kegagalan yang dicari alat ini.
     */
    private class FakeAdapter : OltAdapter {
        override val vendor: String get() = VENDOR

        override val oidPlan: List<OidRole> get() = listOf(
            OidRole("SERIAL", "Serial number ONU", "1.3.6.1.4.1.1.1", essential = true) {
                it.trim().takeIf(String::isNotBlank)
            },
            OidRole("RX_POWER", "Redaman terima (RX)", "1.3.6.1.4.1.1.2", essential = true) { raw ->
                raw.toLongOrNull()?.let { it / 100.0 }?.takeIf { it in -50.0..10.0 }?.let { "$it dBm" }
            },
            OidRole("STATUS", "Status ONU", "1.3.6.1.4.1.1.3", essential = true) { raw ->
                mapOf("1" to "ONLINE", "2" to "OFFLINE")[raw]
            },
            OidRole("UPTIME", "Lama menyala", null),
        )

        override fun probe(target: OltTarget): ProbeResult = throw UnsupportedOperationException()
        override fun pollOnus(target: OltTarget) = throw UnsupportedOperationException()

        companion object {
            const val VENDOR = "FAKEVENDOR"
        }
    }

    private class FakeProbe(
        private val samples: Map<String, List<SnmpSample>> = emptyMap(),
        private val greetFailure: String? = null,
    ) : OltSnmpProbePort {
        val walkCalls = mutableListOf<List<String>>()

        override fun greet(target: SnmpProbeTarget): SnmpGreeting {
            greetFailure?.let { throw SnmpProbeFailure(it) }
            return SnmpGreeting("Fake OLT v1", roundTripMillis = 12)
        }

        override fun walk(target: SnmpProbeTarget, rootOids: List<String>): Map<String, List<SnmpSample>> {
            walkCalls += rootOids
            // Seperti adapter sungguhan: OID yang tak dijawab tetap muncul sebagai daftar kosong.
            return rootOids.associateWith { samples[it].orEmpty() }
        }
    }

    private class StubNetworkApi(private val olt: OltPollingTarget?) : NetworkApi {
        override fun findPollingTargets(oltIds: Set<UUID>): List<OltPollingTarget> = listOfNotNull(olt)

        override fun findOdp(id: UUID): OdpRef? = throw UnsupportedOperationException()
        override fun requireOdp(id: UUID): OdpRef = throw UnsupportedOperationException()
        override fun findOdpsByIds(ids: Set<UUID>): List<OdpRef> = throw UnsupportedOperationException()
        override fun odpsInArea(areaIds: Set<UUID>?): List<OdpRef> = throw UnsupportedOperationException()
        override fun findOdc(id: UUID): OdcRef? = throw UnsupportedOperationException()
        override fun requireOdc(id: UUID): OdcRef = throw UnsupportedOperationException()
        override fun assertOdpPortAssignable(odpId: UUID, portNumber: Int, occupiedPorts: Set<Int>) =
            throw UnsupportedOperationException()

        override fun resnapCablesForMovedCustomer(customerId: UUID, coord: Coordinate) =
            throw UnsupportedOperationException()

        override fun upstreamOf(odpId: UUID): UpstreamPath = throw UnsupportedOperationException()
        override fun renderMapTile(z: Int, x: Int, y: Int, areaIds: Set<UUID>?): ByteArray =
            throw UnsupportedOperationException()

        override fun findOltByCode(code: String): OltRef? = throw UnsupportedOperationException()
        override fun findOltsByIds(ids: Set<UUID>): List<OltRef> = throw UnsupportedOperationException()
        override fun listAllOltIds(): Set<UUID> = throw UnsupportedOperationException()
        override fun findSite(id: UUID): SiteRef? = throw UnsupportedOperationException()
        override fun oltsAtSite(siteId: UUID): List<OltRef> = throw UnsupportedOperationException()
        override fun cablesTouchingNodes(nodeIds: Set<UUID>): List<CablePath> =
            throw UnsupportedOperationException()

        override fun downstreamDeviceIds(oltIds: Set<UUID>, odcIds: Set<UUID>): DownstreamIds =
            throw UnsupportedOperationException()

        override fun odpIdsUnderPonPort(ponPortId: UUID): Set<UUID> = throw UnsupportedOperationException()
        override fun topologyUnderPonPort(ponPortId: UUID): PonPortTopology? =
            throw UnsupportedOperationException()

        override fun candidateOdpsUnderPonPort(oltId: UUID, ponPortLabel: String?): List<OdpRef> =
            throw UnsupportedOperationException()

        override fun cutImpact(cableId: UUID): CableCutImpact = throw UnsupportedOperationException()
    }
}

package com.duluin.ftth.gis

import com.duluin.ftth.bng.BngApi
import com.duluin.ftth.common.domain.UuidV7
import com.duluin.ftth.common.domain.error.NotFoundException
import com.duluin.ftth.common.domain.geo.Coordinate
import com.duluin.ftth.common.security.CurrentUserProvider
import com.duluin.ftth.customer.CustomerApi
import com.duluin.ftth.gis.application.service.MapService
import com.duluin.ftth.monitoring.MonitoringApi
import com.duluin.ftth.network.CableCutImpact
import com.duluin.ftth.network.CablePath
import com.duluin.ftth.network.DownstreamIds
import com.duluin.ftth.network.NetworkApi
import com.duluin.ftth.network.OdcBranch
import com.duluin.ftth.network.OdcRef
import com.duluin.ftth.network.OdpRef
import com.duluin.ftth.network.OltPollingTarget
import com.duluin.ftth.network.OltRef
import com.duluin.ftth.network.PonPortTopology
import com.duluin.ftth.network.SiteRef
import com.duluin.ftth.network.UpstreamPath
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Drill-down PON → ODC → ODP (FAT) harus mengrol-up utilisasi port dengan benar:
 * jumlah port terpakai per ODP (dari customer) → total per ODC → total per PON,
 * dengan persen dibulatkan. ODP tanpa penghuni dihitung 0, dan urutan cabang dari
 * network dipertahankan. PON port tak dikenal melempar NotFound.
 */
class MapServiceInspectPonPortTest {

    private val ponPortId = UuidV7.generate()
    private val oltId = UuidV7.generate()

    // ODC A (1:8, berenergi) → ODP1 kapasitas 8, ODP2 kapasitas 16
    private val odcA = UuidV7.generate()
    private val odp1 = UuidV7.generate()
    private val odp2 = UuidV7.generate()
    // ODC B (1:4, tak berenergi) → ODP3 kapasitas 8
    private val odcB = UuidV7.generate()
    private val odp3 = UuidV7.generate()

    @Test
    fun `merol-up utilisasi port dari ODP ke ODC ke PON`() {
        val service = service(
            network = StubNetworkApi(topology = sampleTopology()),
            customer = StubCustomerApi(usedByOdp = mapOf(odp1 to 4L, odp2 to 8L, odp3 to 2L)),
        )

        val view = service.inspectPonPort(ponPortId)

        assertThat(view.label).isEqualTo("1/1/1")
        assertThat(view.oltId).isEqualTo(oltId)
        assertThat(view.odcCount).isEqualTo(2)
        assertThat(view.odpCount).isEqualTo(3)
        // Total port: (8+16) + 8 = 32; terpakai: (4+8) + 2 = 14; 14/32 = 43,75% → 44%.
        assertThat(view.capacity).isEqualTo(32)
        assertThat(view.used).isEqualTo(14)
        assertThat(view.utilizationPercent).isEqualTo(44)

        // Urutan cabang dari network dipertahankan.
        val (branchA, branchB) = view.odcs
        assertThat(branchA.code).isEqualTo("ODC-A")
        assertThat(branchA.energized).isTrue()
        assertThat(branchA.legCapacity).isEqualTo(8)
        assertThat(branchA.odpCount).isEqualTo(2)
        assertThat(branchA.capacity).isEqualTo(24)
        assertThat(branchA.used).isEqualTo(12)
        assertThat(branchA.utilizationPercent).isEqualTo(50)
        assertThat(branchA.odps.map { it.code }).containsExactly("ODP-1", "ODP-2")
        assertThat(branchA.odps.map { it.used }).containsExactly(4, 8)

        assertThat(branchB.code).isEqualTo("ODC-B")
        assertThat(branchB.energized).isFalse()
        assertThat(branchB.legCapacity).isEqualTo(4)
        assertThat(branchB.odpCount).isEqualTo(1)
        assertThat(branchB.capacity).isEqualTo(8)
        assertThat(branchB.used).isEqualTo(2)
        assertThat(branchB.utilizationPercent).isEqualTo(25)
    }

    @Test
    fun `ODP tanpa penghuni dihitung nol tanpa galat`() {
        val service = service(
            network = StubNetworkApi(topology = sampleTopology()),
            customer = StubCustomerApi(usedByOdp = emptyMap()),
        )

        val view = service.inspectPonPort(ponPortId)

        assertThat(view.used).isEqualTo(0)
        assertThat(view.utilizationPercent).isEqualTo(0)
        assertThat(view.odcs.flatMap { it.odps }.map { it.used }).containsOnly(0)
    }

    @Test
    fun `PON port tak dikenal melempar NotFound`() {
        val service = service(
            network = StubNetworkApi(topology = null),
            customer = StubCustomerApi(usedByOdp = emptyMap()),
        )

        assertThatThrownBy { service.inspectPonPort(ponPortId) }
            .isInstanceOf(NotFoundException::class.java)
    }

    // --- Perkakas uji ---

    private fun sampleTopology() = PonPortTopology(
        ponPortId = ponPortId,
        label = "1/1/1",
        oltId = oltId,
        odcs = listOf(
            OdcBranch(
                odc = odcRef(odcA, "ODC-A", capacity = 8, energized = true),
                odps = listOf(
                    odpRef(odp1, "ODP-1", capacity = 8, odcId = odcA),
                    odpRef(odp2, "ODP-2", capacity = 16, odcId = odcA),
                ),
            ),
            OdcBranch(
                odc = odcRef(odcB, "ODC-B", capacity = 4, energized = false),
                odps = listOf(odpRef(odp3, "ODP-3", capacity = 8, odcId = odcB)),
            ),
        ),
    )

    private fun odcRef(id: UUID, code: String, capacity: Int, energized: Boolean) = OdcRef(
        id = id, code = code, name = code, location = LOC, capacity = capacity,
        ponPortId = ponPortId, energized = energized,
    )

    private fun odpRef(id: UUID, code: String, capacity: Int, odcId: UUID) = OdpRef(
        id = id, code = code, name = code, location = LOC, capacity = capacity,
        areaId = null, odcId = odcId, active = true,
    )

    private fun service(network: NetworkApi, customer: CustomerApi) = MapService(
        networkApi = network,
        customerApi = customer,
        monitoringApi = ThrowingMonitoringApi(),
        bngApi = ThrowingBngApi(),
        currentUser = ThrowingCurrentUser(),
    )

    private class StubNetworkApi(private val topology: PonPortTopology?) : NetworkApi {
        override fun topologyUnderPonPort(ponPortId: UUID): PonPortTopology? = topology

        override fun findOdp(id: UUID): OdpRef? = throw UnsupportedOperationException()
        override fun requireOdp(id: UUID): OdpRef = throw UnsupportedOperationException()
        override fun findOdpsByIds(ids: Set<UUID>): List<OdpRef> = throw UnsupportedOperationException()
        override fun odpsInArea(areaIds: Set<UUID>?): List<OdpRef> = throw UnsupportedOperationException()
        override fun findOdc(id: UUID): OdcRef? = throw UnsupportedOperationException()
        override fun requireOdc(id: UUID): OdcRef = throw UnsupportedOperationException()
        override fun assertOdpPortAssignable(odpId: UUID, portNumber: Int, occupiedPorts: Set<Int>) =
            throw UnsupportedOperationException()

        override fun upstreamOf(odpId: UUID): UpstreamPath = throw UnsupportedOperationException()
        override fun renderMapTile(z: Int, x: Int, y: Int, areaIds: Set<UUID>?): ByteArray =
            throw UnsupportedOperationException()

        override fun findOltByCode(code: String): OltRef? = throw UnsupportedOperationException()
        override fun findOltsByIds(ids: Set<UUID>): List<OltRef> = throw UnsupportedOperationException()
        override fun listAllOltIds(): Set<UUID> = throw UnsupportedOperationException()
        override fun findSite(id: UUID): SiteRef? = throw UnsupportedOperationException()
        override fun oltsAtSite(siteId: UUID): List<OltRef> = throw UnsupportedOperationException()
        override fun findPollingTargets(oltIds: Set<UUID>): List<OltPollingTarget> =
            throw UnsupportedOperationException()

        override fun cablesTouchingNodes(nodeIds: Set<UUID>): List<CablePath> =
            throw UnsupportedOperationException()

        override fun downstreamDeviceIds(oltIds: Set<UUID>, odcIds: Set<UUID>): DownstreamIds =
            throw UnsupportedOperationException()

        override fun odpIdsUnderPonPort(ponPortId: UUID): Set<UUID> = throw UnsupportedOperationException()
        override fun candidateOdpsUnderPonPort(oltId: UUID, ponPortLabel: String?): List<OdpRef> =
            throw UnsupportedOperationException()

        override fun cutImpact(cableId: UUID): CableCutImpact = throw UnsupportedOperationException()
    }

    private class StubCustomerApi(private val usedByOdp: Map<UUID, Long>) : CustomerApi {
        override fun countOccupantsByOdp(odpIds: Set<UUID>): Map<UUID, Long> =
            usedByOdp.filterKeys { it in odpIds }

        override fun findCustomer(id: UUID) = throw UnsupportedOperationException()
        override fun findCustomersByIds(ids: Set<UUID>) = throw UnsupportedOperationException()
        override fun findSubscription(id: UUID) = throw UnsupportedOperationException()
        override fun findSubscriptionsByCustomer(customerId: UUID) = throw UnsupportedOperationException()
        override fun findOccupantsOfOdp(odpId: UUID) = throw UnsupportedOperationException()
        override fun findAwaitingInstallation(areaIds: Set<UUID>?) = throw UnsupportedOperationException()
        override fun findPlacementOf(customerId: UUID) = throw UnsupportedOperationException()
        override fun occupiedPortsOn(odpId: UUID) = throw UnsupportedOperationException()
        override fun renderMapTile(z: Int, x: Int, y: Int, areaIds: Set<UUID>?) = throw UnsupportedOperationException()
        override fun findOnusBySerialNumbers(serialNumbers: Set<String>) = throw UnsupportedOperationException()
        override fun placementsForOnus(onuIds: Set<UUID>) = throw UnsupportedOperationException()
        override fun recordObservedOnuStatuses(statuses: Map<UUID, String>) = throw UnsupportedOperationException()
        override fun provisionOnu(command: com.duluin.ftth.customer.ProvisionOnuCommand) =
            throw UnsupportedOperationException()

        override fun findBillableSubscriptions() = throw UnsupportedOperationException()
        override fun findBillableSubscription(subscriptionId: UUID) = throw UnsupportedOperationException()
        override fun isolateForBilling(subscriptionId: UUID) = throw UnsupportedOperationException()
        override fun reactivateForBilling(subscriptionId: UUID) = throw UnsupportedOperationException()
        override fun activateForInstallation(subscriptionId: UUID) = throw UnsupportedOperationException()
        override fun terminateForDismantle(subscriptionId: UUID) = throw UnsupportedOperationException()
        override fun registerCustomer(command: com.duluin.ftth.customer.RegisterCustomerCommand) =
            throw UnsupportedOperationException()

        override fun openSubscription(customerId: UUID, planId: UUID, monthlyFeeOverride: java.math.BigDecimal?) =
            throw UnsupportedOperationException()

        override fun subscriberStats() = throw UnsupportedOperationException()
        override fun updateCustomerBiodata(command: com.duluin.ftth.customer.UpdateCustomerBiodataCommand) = throw UnsupportedOperationException()
        override fun activateImportedSubscription(subscriptionId: UUID, activatedAt: java.time.Instant?, billingDayOfMonth: Int?) = throw UnsupportedOperationException()
        override fun overrideSubscriptionBillingDay(subscriptionId: UUID, billingDayOfMonth: Int?) = throw UnsupportedOperationException()
        override fun findExportRows(subscriptionIds: Set<java.util.UUID>): List<com.duluin.ftth.customer.CustomerExportRow> = throw UnsupportedOperationException()
    }

    private class ThrowingMonitoringApi : MonitoringApi {
        override fun activeImpacts() = throw UnsupportedOperationException()
        override fun latestMetricsByOnuIds(onuIds: Set<UUID>) = throw UnsupportedOperationException()
    }

    private class ThrowingBngApi : BngApi {
        override fun findSubscriberSession(customerId: UUID) = throw UnsupportedOperationException()
        override fun provisionAccess(command: com.duluin.ftth.bng.ProvisionAccessSpec) =
            throw UnsupportedOperationException()

        override fun resolveNasForArea(areaId: UUID) = throw UnsupportedOperationException()
        override fun fetchPppSecretsFromNas(nasId: UUID) = throw UnsupportedOperationException()
        override fun activeSubscriberLiveness() = throw UnsupportedOperationException()
        override fun resolveNasByName(name: String) = throw UnsupportedOperationException()
        override fun findAccessByUsername(username: String) = throw UnsupportedOperationException()
        override fun updateAccessFromImport(accessId: UUID, planId: UUID, nasId: UUID?, secret: String?) = throw UnsupportedOperationException()
        override fun exportAccesses(): List<com.duluin.ftth.bng.AccessExportRef> = throw UnsupportedOperationException()
    }

    private class ThrowingCurrentUser : CurrentUserProvider {
        override fun currentOrNull() = throw UnsupportedOperationException()
    }

    private companion object {
        val LOC = Coordinate(106.8, -6.2)
    }
}

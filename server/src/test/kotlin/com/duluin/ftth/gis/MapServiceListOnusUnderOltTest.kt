package com.duluin.ftth.gis

import com.duluin.ftth.bng.BngApi
import com.duluin.ftth.common.domain.UuidV7
import com.duluin.ftth.common.domain.geo.Coordinate
import com.duluin.ftth.common.security.CurrentUserProvider
import com.duluin.ftth.customer.CustomerApi
import com.duluin.ftth.customer.OdpOccupant
import com.duluin.ftth.gis.application.service.MapService
import com.duluin.ftth.monitoring.MonitoringApi
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
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Daftar ONU per-OLT harus disusun dalam jumlah query konstan — SATU batch penghuni
 * untuk seluruh ODP di bawah OLT, bukan per-ODP (N+1). Uji ini mengunci sifat itu:
 * [MapService.listOnusUnderOlt] memanggil [CustomerApi.findOccupantsForOdps] tepat
 * sekali dengan semua ODP sekaligus dan tak pernah menyentuh [CustomerApi.findOccupantsOfOdp].
 * Sekaligus memverifikasi baris diperkaya kode ODP dan terurut per kode ODP lalu port.
 */
class MapServiceListOnusUnderOltTest {

    private val oltId = UuidV7.generate()
    private val odp1 = UuidV7.generate()
    private val odp2 = UuidV7.generate()

    @Test
    fun `menyusun daftar dalam satu batch, bukan N+1, dan terurut per ODP lalu port`() {
        val customer = RecordingCustomerApi(
            occupantsByOdp = mapOf(
                // Sengaja acak: odp2 dulu, dan port 2 sebelum 1, untuk menguji pengurutan.
                odp2 to listOf(occ(port = 1, name = "Budi", serial = "SN-B")),
                odp1 to listOf(
                    occ(port = 2, name = "Andi", serial = "SN-A2"),
                    occ(port = 1, name = "Ani", serial = "SN-A1"),
                ),
            ),
        )
        val service = service(
            network = StubNetworkApi(
                odpIdsUnderOlt = setOf(odp1, odp2),
                odpCodes = mapOf(odp1 to "ODP-1", odp2 to "ODP-2"),
            ),
            customer = customer,
        )

        val view = service.listOnusUnderOlt(oltId)

        // Satu batch untuk semua ODP; per-ODP tak pernah dipanggil.
        assertThat(customer.forOdpsCalls).isEqualTo(1)
        assertThat(customer.forOdpsArgs.single()).containsExactlyInAnyOrder(odp1, odp2)
        assertThat(customer.ofOdpCalls).isEqualTo(0)

        // Terurut per kode ODP (ODP-1 dulu) lalu port (1 sebelum 2), lintas ODP.
        assertThat(view.oltId).isEqualTo(oltId)
        assertThat(view.onuCount).isEqualTo(3)
        assertThat(view.onus.map { it.customerName }).containsExactly("Ani", "Andi", "Budi")
        assertThat(view.onus.map { it.serialNumber }).containsExactly("SN-A1", "SN-A2", "SN-B")
        assertThat(view.onus.map { it.odpCode }).containsExactly("ODP-1", "ODP-1", "ODP-2")
        assertThat(view.onus.map { it.portNumber }).containsExactly(1, 2, 1)
        assertThat(view.onus.map { it.odpId }).containsExactly(odp1, odp1, odp2)
    }

    @Test
    fun `OLT tanpa ODP hilir mengembalikan kosong tanpa menyentuh customer`() {
        val customer = RecordingCustomerApi(occupantsByOdp = emptyMap())
        val service = service(
            network = StubNetworkApi(odpIdsUnderOlt = emptySet(), odpCodes = emptyMap()),
            customer = customer,
        )

        val view = service.listOnusUnderOlt(oltId)

        assertThat(view.onuCount).isEqualTo(0)
        assertThat(view.onus).isEmpty()
        assertThat(customer.forOdpsCalls).isEqualTo(0)
        assertThat(customer.ofOdpCalls).isEqualTo(0)
    }

    // --- Perkakas uji ---

    private fun occ(port: Int, name: String, serial: String) = OdpOccupant(
        portNumber = port,
        customerId = UuidV7.generate(),
        customerCode = "C-$serial",
        customerName = name,
        phone = null,
        location = LOC,
        onuId = UuidV7.generate(),
        onuSerialNumber = serial,
        onuStatus = "ONLINE",
        opticalHealth = "HEALTHY",
        installRxPowerDbm = -20.0,
        subscriptionPackage = "Paket",
        subscriptionStatus = "ACTIVE",
    )

    private fun service(network: NetworkApi, customer: CustomerApi) = MapService(
        networkApi = network,
        customerApi = customer,
        monitoringApi = ThrowingMonitoringApi(),
        bngApi = ThrowingBngApi(),
        currentUser = ThrowingCurrentUser(),
    )

    private inner class StubNetworkApi(
        private val odpIdsUnderOlt: Set<UUID>,
        private val odpCodes: Map<UUID, String>,
    ) : NetworkApi {
        override fun downstreamDeviceIds(oltIds: Set<UUID>, odcIds: Set<UUID>): DownstreamIds {
            check(oltIds == setOf(oltId) && odcIds.isEmpty()) { "tak terduga: $oltIds / $odcIds" }
            return DownstreamIds(odcIds = emptySet(), odpIds = odpIdsUnderOlt)
        }

        override fun findOdpsByIds(ids: Set<UUID>): List<OdpRef> =
            ids.filter { it in odpCodes }.map { odpRef(it, odpCodes.getValue(it)) }

        private fun odpRef(id: UUID, code: String) = OdpRef(
            id = id, code = code, name = code, location = LOC, capacity = 8,
            areaId = null, odcId = null, active = true,
        )

        override fun findOdp(id: UUID): OdpRef? = throw UnsupportedOperationException()
        override fun requireOdp(id: UUID): OdpRef = throw UnsupportedOperationException()
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

        override fun odpIdsUnderPonPort(ponPortId: UUID): Set<UUID> = throw UnsupportedOperationException()
        override fun topologyUnderPonPort(ponPortId: UUID): PonPortTopology? = throw UnsupportedOperationException()
        override fun candidateOdpsUnderPonPort(oltId: UUID, ponPortLabel: String?): List<OdpRef> =
            throw UnsupportedOperationException()

        override fun cutImpact(cableId: UUID): CableCutImpact = throw UnsupportedOperationException()
    }

    private class RecordingCustomerApi(
        private val occupantsByOdp: Map<UUID, List<OdpOccupant>>,
    ) : CustomerApi {
        var forOdpsCalls = 0
        val forOdpsArgs = mutableListOf<Set<UUID>>()
        var ofOdpCalls = 0

        override fun findOccupantsForOdps(odpIds: Set<UUID>): Map<UUID, List<OdpOccupant>> {
            forOdpsCalls++
            forOdpsArgs.add(odpIds)
            return occupantsByOdp.filterKeys { it in odpIds }
        }

        override fun findOccupantsOfOdp(odpId: UUID): List<OdpOccupant> {
            ofOdpCalls++
            throw UnsupportedOperationException("listOnusUnderOlt harus membatch, bukan per-ODP")
        }

        override fun findCustomer(id: UUID) = throw UnsupportedOperationException()
        override fun findCustomersByIds(ids: Set<UUID>) = throw UnsupportedOperationException()
        override fun findSubscription(id: UUID) = throw UnsupportedOperationException()
        override fun findSubscriptionsByCustomer(customerId: UUID) = throw UnsupportedOperationException()
        override fun findAwaitingInstallation(areaIds: Set<UUID>?) = throw UnsupportedOperationException()
        override fun findPlacementOf(customerId: UUID) = throw UnsupportedOperationException()
        override fun occupiedPortsOn(odpId: UUID) = throw UnsupportedOperationException()
        override fun countOccupantsByOdp(odpIds: Set<UUID>) = throw UnsupportedOperationException()
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
        override fun updateCustomerBiodata(command: com.duluin.ftth.customer.UpdateCustomerBiodataCommand) =
            throw UnsupportedOperationException()

        override fun activateImportedSubscription(subscriptionId: UUID, activatedAt: java.time.Instant?, billingDayOfMonth: Int?) =
            throw UnsupportedOperationException()

        override fun overrideSubscriptionBillingDay(subscriptionId: UUID, billingDayOfMonth: Int?) =
            throw UnsupportedOperationException()

        override fun findExportRows(subscriptionIds: Set<UUID>): List<com.duluin.ftth.customer.CustomerExportRow> =
            throw UnsupportedOperationException()
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
        override fun updateAccessFromImport(accessId: UUID, planId: UUID, nasId: UUID?, secret: String?) =
            throw UnsupportedOperationException()

        override fun exportAccesses(): List<com.duluin.ftth.bng.AccessExportRef> = throw UnsupportedOperationException()
    }

    private class ThrowingCurrentUser : CurrentUserProvider {
        override fun currentOrNull() = throw UnsupportedOperationException()
    }

    private companion object {
        val LOC = Coordinate(106.8, -6.2)
    }
}

package com.duluin.ftth.collector.adapter

import com.duluin.ftth.contract.NasTarget
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RouterOsProvisioningAdapterTest {
    private lateinit var server: HttpServer
    private val requests = mutableListOf<String>()

    @BeforeTest
    fun startFixture() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/rest") { exchange ->
            requests += "${exchange.requestMethod} ${exchange.requestURI.path}"
            val response = when (exchange.requestURI.path) {
                "/rest/interface/bridge" ->
                    """[{".id":"*1","name":"br-service","vlan-filtering":"yes","comment":"ftth:t1:i1:bridge"}]"""
                "/rest/interface/bridge/port" ->
                    """[{".id":"*2","bridge":"br-service","interface":"ether2","pvid":"110","ingress-filtering":"yes","frame-types":"admit-only-untagged-and-priority-tagged","comment":"ftth:t1:i1:port:ether2"}]"""
                "/rest/interface/bridge/vlan" ->
                    """[{".id":"*3","bridge":"br-service","vlan-ids":"110","tagged":"ether1,br-service","untagged":"ether2","current-tagged":"ether1,br-service","current-untagged":"ether2","comment":"ftth:t1:i1:bridge-vlan:110"}]"""
                "/rest/interface/vlan" ->
                    """[{".id":"*4","name":"svc-110","interface":"br-service","vlan-id":"110","comment":"ftth:t1:i1:vlan:110"}]"""
                "/rest/interface/pppoe-server/server" ->
                    """[{".id":"*5","interface":"svc-110","disabled":"no","service-name":"ftth-110","pppoe-over-vlan-range":"","comment":"ftth:t1:i1:pppoe:110"}]"""
                "/rest/ip/pool" ->
                    """[{".id":"*6","name":"ftth-110","ranges":"100.64.110.2-100.64.110.254","comment":"ftth:t1:i1:pool:110"}]"""
                "/rest/interface/list" ->
                    """[{".id":"*7","name":"FTTH-CUSTOMER","comment":"ftth:t1:i1:list:customer"}]"""
                "/rest/interface/list/member" ->
                    """[{".id":"*8","list":"FTTH-CUSTOMER","interface":"svc-110","comment":"ftth:t1:i1:list-member:110"}]"""
                "/rest/ip/firewall/filter" ->
                    """[{".id":"*9","chain":"forward","action":"drop","in-interface-list":"FTTH-CUSTOMER","out-interface-list":"FTTH-CUSTOMER","comment":"ftth:t1:i1:firewall:deny-inter-vlan"}]"""
                "/rest/system/resource" ->
                    """[{"platform":"MikroTik","board-name":"CCR2004-16G-2S+","version":"7.20.1"}]"""
                else -> error("Unexpected fixture request ${exchange.requestMethod} ${exchange.requestURI.path}")
            }
            respond(exchange, response)
        }
        server.start()
    }

    @AfterTest
    fun stopFixture() = server.stop(0)

    @Test
    fun `discovers complete normalized state and reports exact fingerprint as provisional`() {
        val adapter = RouterOsProvisioningAdapter(
            clock = Clock.fixed(Instant.parse("2026-09-02T10:00:00Z"), ZoneOffset.UTC),
            allowInsecureHttpForTests = true,
            stateStore = InMemoryRouterOsProvisioningStateStore(),
        )

        val state = adapter.discover(target())
        val report = adapter.capabilityReport(target())

        assertEquals("*1", state.bridges.single().id)
        assertEquals("ether2", state.bridgePorts.single().interfaceName)
        assertEquals(setOf("ether1", "br-service"), state.bridgeVlans.single().currentTagged)
        assertEquals(setOf("ether2"), state.bridgeVlans.single().currentUntagged)
        assertEquals(110, state.vlanInterfaces.single().vlanId)
        assertEquals("svc-110", state.pppoeServers.single().interfaceName)
        assertEquals("100.64.110.2-100.64.110.254", state.ipPools.single().ranges)
        assertEquals("FTTH-CUSTOMER", state.interfaceLists.single().name)
        assertEquals("svc-110", state.interfaceListMembers.single().interfaceName)
        assertEquals("drop", state.firewallRules.single().action)
        assertEquals("CCR2004-16G-2S+", report.fingerprint.model)
        assertEquals("7.20.1", report.fingerprint.firmware)
        assertTrue("CERTIFICATION_PROVISIONAL" in report.capabilities)
        assertEquals(
            setOf(
                "GET /rest/interface/bridge",
                "GET /rest/interface/bridge/port",
                "GET /rest/interface/bridge/vlan",
                "GET /rest/interface/vlan",
                "GET /rest/interface/pppoe-server/server",
                "GET /rest/ip/pool",
                "GET /rest/interface/list",
                "GET /rest/interface/list/member",
                "GET /rest/ip/firewall/filter",
                "GET /rest/system/resource",
            ),
            requests.toSet(),
        )
    }

    private fun target() = NasTarget(
        nasId = "router-1",
        name = "router-1",
        vendor = "MIKROTIK",
        host = "127.0.0.1",
        adapterType = "ROUTER_OS",
        apiUsername = "provisioner",
        apiSecret = "test-only",
        apiPort = server.address.port,
        apiUseTls = false,
    )

    private fun respond(exchange: HttpExchange, body: String, status: Int = 200) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }
}

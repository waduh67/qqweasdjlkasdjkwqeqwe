package com.karuhun.ftth.fieldservice

import com.karuhun.ftth.inventory.WarehouseMasterHttpFixture
import com.karuhun.ftth.fieldservice.application.port.outbound.IpPingPort
import com.karuhun.ftth.fieldservice.application.service.IpProbeResult
import com.karuhun.ftth.fieldservice.application.service.IpProbeTransactions
import com.karuhun.ftth.fieldservice.application.service.IpProbeWorker
import com.karuhun.ftth.common.tenant.TenantContext
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.BDDMockito.given
import org.mockito.Mockito.anyString
import org.springframework.test.context.bean.override.mockito.MockitoBean
import tools.jackson.databind.JsonNode
import java.time.Instant
import java.util.UUID
import org.awaitility.Awaitility.await
import java.util.concurrent.TimeUnit

class IpBlocksIT : WarehouseMasterHttpFixture() {
    @MockitoBean lateinit var ping: IpPingPort
    private val root="/api/v1/network-inventory"
    private fun ok(method: String,path: String,token: String,input: Any?=null,status: Int=200,key: String=UUID.randomUUID().toString()): JsonNode {
        val response=request(method,root+path,token,input?.let(mapper::writeValueAsString),key)
        assertThat(response.status).withFailMessage(response.contentAsString).isEqualTo(status)
        return mapper.readTree(response.contentAsString)
    }
    @Test fun subnetReusesIdentityAndManualOwnerWhileReservingBoundaryAddresses() {
        val owner=tenant()
        val old=ok("POST","/ips",owner,mapOf("address" to "157.15.123.1","subnet" to "","gateway" to "","vendorId" to null,"usedBy" to "Router PT ABC"),201)
        val input=mapOf("cidr" to "157.15.123.80/23","vendorId" to null,"gateway" to "","notes" to "Blok public")
        assertThat(ok("POST","/blocks/preview",owner,input).path("existingCount").asInt()).isEqualTo(1)
        val block=ok("POST","/blocks",owner,input,201)
        assertThat(block.path("cidr").asString()).isEqualTo("157.15.122.0/23")
        assertThat(block.path("addressCount").asInt()).isEqualTo(512)
        val id=block.path("id").asString()
        val addresses=(0..5).flatMap { ok("GET","/ips?blockId=$id&page=$it&size=100",owner).path("content").toList() }
        assertThat(addresses).hasSize(512)
        val reused=addresses.single { it.path("address").asString()=="157.15.123.1" }
        assertThat(reused.path("id")).isEqualTo(old.path("id"))
        assertThat(reused.path("usedBy").asString()).isEqualTo("Router PT ABC")
        assertThat(ok("GET","/ips?q=ABC&status=ALLOCATED",owner).path("content").single().path("id")).isEqualTo(old.path("id"))
        val network=addresses.single { it.path("addressRole").asString()=="NETWORK" }
        val forbidden=mapOf("address" to network.path("address").asString(),"subnet" to network.path("subnet").asString(),"gateway" to "","vendorId" to null,"usedBy" to "Forbidden","expectedRevision" to network.path("revision").asLong())
        assertThat(request("PUT",root+"/ips/"+network.path("id").asString(),owner,mapper.writeValueAsString(forbidden)).status).isEqualTo(400)
        assertThat(request("POST",root+"/blocks",owner,mapper.writeValueAsString(input+mapOf("cidr" to "157.15.122.0/24"))).status).isEqualTo(409)
        assertThat(ok("GET","/ips?blockId=$id",tenant()).path("content").isEmpty).isTrue()
    }
    @Test fun monitoringQueuesAllHostsOnceAndAdvancesItsFiveMinuteDeadline() {
        given(ping.ping(anyString())).willReturn(IpProbeResult("UP",Instant.now(),rttMillis=1.0))
        val owner=tenant()
        val block=ok("POST","/blocks",owner,mapOf("cidr" to "203.0.113.0/30"),201)
        val id=block.path("id").asString()
        ok("PUT","/blocks/$id/monitoring",owner,mapOf("expectedRevision" to 0,"enabled" to true))
        val f=fixture(owner)
        val transactions=context.getBean(IpProbeTransactions::class.java)
        repeat(2) { TenantContext.runAs(f.tenant) { transactions.scheduleDue() } }
        val job=f.transaction { jdbc { connection ->
            connection.prepareStatement("SELECT j.id,b.next_check_at>now()+interval '4 minutes' delayed FROM network_probe_job j JOIN network_ip_block b ON b.tenant_id=j.tenant_id AND b.id=j.block_id WHERE j.tenant_id=? AND j.block_id=?").use { statement ->
                statement.setObject(1,f.tenant);statement.setObject(2,UUID.fromString(id))
                statement.executeQuery().use { rows ->
                    assertThat(rows.next()).isTrue();assertThat(rows.getBoolean("delayed")).isTrue()
                    val result=rows.getObject("id",UUID::class.java)
                    assertThat(rows.next()).isFalse();result
                }
            }
        } }
        context.getBean(IpProbeWorker::class.java).tick()
        await().atMost(15,TimeUnit.SECONDS).untilAsserted {
            val completed=ok("GET","/probe-jobs/$job",owner)
            assertThat(completed.path("state").asString()).isEqualTo("COMPLETED")
            assertThat(completed.path("up").asInt()).isEqualTo(2)
        }
    }
    @Test fun changingStandaloneAddressClearsItsPreviousProbe() {
        given(ping.ping(anyString())).willReturn(IpProbeResult("UP",Instant.now(),rttMillis=1.0))
        val owner=tenant()
        val ip=ok("POST","/ips",owner,mapOf("address" to "203.0.113.40","subnet" to "","gateway" to "","vendorId" to null),201)
        val id=ip.path("id").asString()
        val job=ok("POST","/ips/$id/ping",owner,status=202).path("id").asString()
        await().atMost(15,TimeUnit.SECONDS).untilAsserted {
            assertThat(ok("GET","/probe-jobs/$job",owner).path("state").asString()).isEqualTo("COMPLETED")
        }
        assertThat(ok("GET","/ips?q=203.0.113.40",owner).path("content").single().path("probe").path("status").asString()).isEqualTo("UP")
        val changed=ok("PUT","/ips/$id",owner,mapOf("address" to "203.0.113.41","subnet" to "","gateway" to "","vendorId" to null,"expectedRevision" to 0))
        assertThat(changed.path("probe").isNull).isTrue()
    }
    @Test fun manualProbeCompletesEveryHostAndKeepsAllocationSeparateFromReachability() {
        given(ping.ping(anyString())).willAnswer { call -> IpProbeResult(if(call.getArgument<String>(0).endsWith("1")) "UP" else "DOWN",Instant.now(),rttMillis=1.25) }
        val owner=tenant()
        val block=ok("POST","/blocks",owner,mapOf("cidr" to "203.0.113.0/30","vendorId" to null),201)
        val id=block.path("id").asString();val key=UUID.randomUUID().toString()
        val queued=ok("POST","/blocks/$id/ping",owner,status=202,key=key);val job=queued.path("id").asString()
        await().atMost(15,TimeUnit.SECONDS).untilAsserted {
            val completed=ok("GET","/probe-jobs/$job",owner)
            assertThat(completed.path("state").asString()).isEqualTo("COMPLETED")
            assertThat(completed.path("total").asInt()).isEqualTo(2)
            assertThat(completed.path("completed").asInt()).isEqualTo(2)
            assertThat(completed.path("up").asInt()).isEqualTo(1)
            assertThat(completed.path("down").asInt()).isEqualTo(1)
        }
        assertThat(ok("POST","/blocks/$id/ping",owner,status=202,key=key).path("id").asString()).isEqualTo(job)
        val up=ok("GET","/ips?blockId=$id&reachability=UP&status=AVAILABLE",owner).path("content").single()
        assertThat(up.path("usedBy").asString()).isEmpty()
        assertThat(up.path("probe").path("status").asString()).isEqualTo("UP")
        assertThat(request("GET",root+"/probe-jobs/$job",tenant()).status).isEqualTo(404)
    }
}

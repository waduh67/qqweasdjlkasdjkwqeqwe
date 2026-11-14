package com.karuhun.ftth.fieldservice.application.service

import com.karuhun.ftth.common.tenant.TenantContext
import com.karuhun.ftth.fieldservice.adapter.outbound.persistence.*
import com.karuhun.ftth.fieldservice.application.port.outbound.IpPingPort
import com.karuhun.ftth.tenancy.TenantApi
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.event.TransactionalEventListener
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore

data class ClaimedIpProbe(val tenant: UUID,val job: UUID,val ip: UUID,val address: String,val token: UUID)

@Component
class IpProbeTransactions(private val store: B2BStore) {
    private val mapper = jacksonObjectMapper()
    @Transactional(propagation=Propagation.REQUIRES_NEW) fun scheduleDue() = store.use { sql ->
        sql.lock()
        val due = sql.query("SELECT id FROM network_ip_block WHERE tenant_id=? AND monitoring_enabled AND next_check_at<=now() ORDER BY next_check_at LIMIT 10 FOR UPDATE SKIP LOCKED",sql.tenant) { it.uuid("id") }
        due.forEach { id ->
            IpProbeService.queue(sql,null,id,null)
            sql.update("UPDATE network_ip_block SET next_check_at=now()+interval '5 minutes' WHERE tenant_id=? AND id=?",sql.tenant,id)
        }
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW) fun claim(): ClaimedIpProbe? = store.use { sql ->
        sql.update("""UPDATE network_probe_job j SET state='COMPLETED',completed_at=now() WHERE tenant_id=? AND state<>'COMPLETED'
            AND NOT EXISTS(SELECT FROM network_probe_item i WHERE i.tenant_id=j.tenant_id AND i.job_id=j.id AND i.state<>'COMPLETED')""",sql.tenant)
        val job=sql.query("""SELECT j.id FROM network_probe_job j WHERE j.tenant_id=? AND j.state<>'COMPLETED'
            AND EXISTS(SELECT FROM network_probe_item i JOIN network_ip_probe p ON p.tenant_id=i.tenant_id AND p.ip_id=i.ip_id
                WHERE i.tenant_id=j.tenant_id AND i.job_id=j.id AND i.state<>'COMPLETED'
                AND (i.lease_until IS NULL OR i.lease_until<now()) AND (p.lease_until IS NULL OR p.lease_until<now()))
            ORDER BY j.created_at LIMIT 1 FOR UPDATE SKIP LOCKED""",sql.tenant) { it.uuid("id") }.singleOrNull() ?: return@use null
        val token = UUID.randomUUID()
        val row = sql.query("""SELECT i.job_id,i.ip_id,host(i.address) address FROM network_probe_item i
            JOIN network_ip_probe p ON p.tenant_id=i.tenant_id AND p.ip_id=i.ip_id
            JOIN network_probe_job j ON j.tenant_id=i.tenant_id AND j.id=i.job_id
            WHERE i.tenant_id=? AND i.job_id=? AND j.state<>'COMPLETED' AND i.state<>'COMPLETED'
                AND (i.lease_until IS NULL OR i.lease_until<now()) AND (p.lease_until IS NULL OR p.lease_until<now())
            ORDER BY j.created_at,i.address LIMIT 1 FOR UPDATE OF i,p SKIP LOCKED""",sql.tenant,job) {
                ClaimedIpProbe(sql.tenant,it.uuid("job_id"),it.uuid("ip_id"),it.getString("address"),token)
            }.singleOrNull() ?: return@use null
        sql.update("UPDATE network_probe_item SET state='RUNNING',lease_token=?,lease_until=now()+interval '30 seconds' WHERE tenant_id=? AND job_id=? AND ip_id=?",token,sql.tenant,row.job,row.ip)
        sql.update("UPDATE network_ip_probe SET lease_token=?,lease_until=now()+interval '30 seconds' WHERE tenant_id=? AND ip_id=?",token,sql.tenant,row.ip)
        sql.update("UPDATE network_probe_job SET state='RUNNING' WHERE tenant_id=? AND id=?",sql.tenant,row.job)
        row
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW) fun finish(item: ClaimedIpProbe, result: IpProbeResult) = store.use { sql ->
        sql.query("SELECT id FROM network_probe_job WHERE tenant_id=? AND id=? FOR UPDATE",sql.tenant,item.job) { it.uuid("id") }
        val json = mapper.writeValueAsString(result)
        if (sql.update("""UPDATE network_probe_item SET state='COMPLETED',result=?::jsonb,lease_token=NULL,lease_until=NULL
            WHERE tenant_id=? AND job_id=? AND ip_id=? AND lease_token=?""",json,sql.tenant,item.job,item.ip,item.token)==0) return@use
        sql.update("""UPDATE network_ip_probe probe SET result=CASE WHEN EXISTS(SELECT FROM network_public_ip ip
            WHERE ip.tenant_id=probe.tenant_id AND ip.id=probe.ip_id AND ip.address=?::inet)
            AND (result IS NULL OR (result->>'checkedAt')::timestamptz<=?::timestamptz) THEN ?::jsonb ELSE result END,
            lease_token=NULL,lease_until=NULL WHERE tenant_id=? AND ip_id=? AND lease_token=?""",
            item.address,result.checkedAt.toString(),json,sql.tenant,item.ip,item.token)
        sql.update("""UPDATE network_probe_job j SET state='COMPLETED',completed_at=now() WHERE tenant_id=? AND id=?
            AND NOT EXISTS(SELECT FROM network_probe_item WHERE tenant_id=j.tenant_id AND job_id=j.id AND state<>'COMPLETED')""",sql.tenant,item.job)
    }
}

@Component
class IpProbeWorker(private val transactions: IpProbeTransactions, private val ping: IpPingPort, private val tenants: TenantApi,
    @Value("\${ftth.scheduling.enabled:true}") private val scheduling: Boolean) {
    private val pool = Executors.newFixedThreadPool(32) { task -> Thread(task,"public-ip-ping").apply { isDaemon=true } }
    private val capacity = Semaphore(32)
    private val log = LoggerFactory.getLogger(javaClass)
    @TransactionalEventListener fun requested(event: IpProbeRequested) { pool.execute { dispatch(event.tenantId) } }
    @Scheduled(fixedDelay=1000) fun tick() {
        tenants.findActiveTenantIds().filter { it!=tenants.platformTenantId() }.shuffled().forEach { tenant ->
            runCatching { if(scheduling) TenantContext.runAs(tenant) { transactions.scheduleDue() }; dispatch(tenant) }
                .onFailure { log.warn("Pemeriksaan IP tenant {} gagal: {}",tenant,it.message) }
        }
    }
    private fun dispatch(tenant: UUID) {
        if(pool.isShutdown) return
        while (capacity.tryAcquire()) {
            val item = try { TenantContext.runAs(tenant) { transactions.claim() } }
                catch (error: Exception) { capacity.release(); log.warn("Tidak dapat mengambil pemeriksaan IP: {}",error.message); return }
            if (item==null) { capacity.release(); return }
            pool.execute {
                try { val result=ping.ping(item.address); TenantContext.runAs(tenant) { transactions.finish(item,result) } }
                catch (error: Exception) { log.warn("Pemeriksaan IP {} gagal: {}",item.ip,error.message) }
                finally { capacity.release(); dispatch(tenant) }
            }
        }
    }
    @PreDestroy fun stop() { pool.shutdownNow() }
}

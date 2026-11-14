package com.karuhun.ftth.fieldservice.application.service

import com.karuhun.ftth.common.domain.error.*
import com.karuhun.ftth.common.tenant.TenantContext
import com.karuhun.ftth.fieldservice.adapter.outbound.persistence.*
import com.karuhun.ftth.iam.CurrentAuthorityApi
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

data class IpProbeRequested(val tenantId: UUID)

@Service
@Transactional
class IpProbeService(private val inventory: NetworkInventoryService, private val authority: CurrentAuthorityApi,
    private val store: B2BStore, private val events: ApplicationEventPublisher) {
    fun start(ip: UUID?, block: UUID?, key: UUID): IpProbeJobView {
        val actor = inventory.access(false)
        val current = authority.lockCurrent()
        if (!current.platformAdmin && "network.ip.check" !in current.permissions) throw AccessDeniedException("Tidak memiliki izin pemeriksaan ping")
        inventory.assertWritable()
        val job = inventory.mutate(actor,key,"ip-ping",ip ?: block,mapOf("ipId" to ip,"blockId" to block),IpProbeJobView::class.java) { sql ->
            val result = queue(sql,ip,block,actor)
            result
        }
        events.publishEvent(IpProbeRequested(TenantContext.tenantId()))
        return job
    }
    fun job(id: UUID): IpProbeJobView { inventory.access(false); return store.use { job(it,id) } }
    companion object {
        internal fun queue(sql: B2BSql, ip: UUID?, block: UUID?, actor: UUID?): IpProbeJobView {
            if ((ip==null)==(block==null)) throw ValidationException("Pilih satu IP atau blok")
            if (block != null) {
                if (sql.query("SELECT id FROM network_ip_block WHERE tenant_id=? AND id=?",sql.tenant,block) { true }.isEmpty())
                    throw NotFoundException("Blok IP tidak ditemukan")
                val existing = sql.query("SELECT id FROM network_probe_job WHERE tenant_id=? AND block_id=? AND state<>'COMPLETED'",sql.tenant,block) { it.uuid("id") }.singleOrNull()
                if (existing != null) return job(sql,existing)
            } else if (sql.ip(requireNotNull(ip)).addressRole != "HOST") throw ValidationException("Network dan broadcast tidak diperiksa")
            val id = UUID.randomUUID()
            sql.update("INSERT INTO network_probe_job(id,tenant_id,block_id,actor_id) VALUES (?,?,?,?)",id,sql.tenant,block,actor)
            sql.update("""INSERT INTO network_probe_item(tenant_id,job_id,ip_id,address)
                SELECT tenant_id,?,id,address FROM network_public_ip WHERE tenant_id=? AND address_role='HOST'
                    AND ${if (ip != null) "id=?" else "block_id=?"}""",id,sql.tenant,ip ?: block)
            sql.update("""INSERT INTO network_ip_probe(tenant_id,ip_id) SELECT tenant_id,ip_id FROM network_probe_item
                WHERE tenant_id=? AND job_id=? ON CONFLICT DO NOTHING""",sql.tenant,id)
            return job(sql,id)
        }
        internal fun job(sql: B2BSql,id: UUID): IpProbeJobView = sql.query("""SELECT j.*,
            count(i.ip_id) total,count(i.ip_id) FILTER(WHERE i.state='COMPLETED') completed,
            count(i.ip_id) FILTER(WHERE i.result->>'status'='UP') up,count(i.ip_id) FILTER(WHERE i.result->>'status'='DOWN') down,
            count(i.ip_id) FILTER(WHERE i.result->>'status'='ERROR') errors FROM network_probe_job j
            LEFT JOIN network_probe_item i ON i.tenant_id=j.tenant_id AND i.job_id=j.id WHERE j.tenant_id=? AND j.id=? GROUP BY j.id""",sql.tenant,id) {
                IpProbeJobView(it.uuid("id"),it.getObject("block_id",UUID::class.java),it.getString("state"),it.getInt("total"),
                    it.getInt("completed"),it.getInt("up"),it.getInt("down"),it.getInt("errors"),it.getTimestamp("created_at").toInstant())
            }.singleOrNull() ?: throw NotFoundException("Pemeriksaan tidak ditemukan")
    }
}

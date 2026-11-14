package com.karuhun.ftth.fieldservice.application.service

import com.karuhun.ftth.common.domain.PageRequest
import com.karuhun.ftth.common.domain.error.*
import com.karuhun.ftth.fieldservice.domain.Ipv4Block
import com.karuhun.ftth.fieldservice.adapter.outbound.persistence.*
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
@Transactional
class IpBlockService(private val inventory: NetworkInventoryService, private val network: B2BNetworkService, private val store: B2BStore) {
    fun preview(input: IpBlockInput): IpBlockPreview {
        inventory.access(false)
        val block = Ipv4Block.parse(input.cidr)
        return store.use { sql -> validate(sql, input, block); preview(sql, block) }
    }
    fun list(page: PageRequest) = inventory.access(false).let {
        store.use { sql -> sql.page("$SELECT WHERE b.tenant_id=? ORDER BY b.network,b.id", page, sql.tenant, map=::blockView) }
    }
    fun create(input: IpBlockInput, key: UUID): IpBlockView {
        val actor = inventory.access(true)
        val block = Ipv4Block.parse(input.cidr)
        val value = input.copy(cidr=block.cidr, gateway=network.ipv4(input.gateway), notes=input.notes.trim())
        return inventory.mutate(actor, key, "ip-block", null, value, IpBlockView::class.java) { sql ->
            validate(sql, value, block)
            val id = UUID.randomUUID()
            sql.update("INSERT INTO network_ip_block(id,tenant_id,network,vendor_id,gateway,notes) VALUES (?,?,?::cidr,?,?,?)",
                id, sql.tenant, block.cidr, value.vendorId, value.gateway, value.notes)
            sql.update("""INSERT INTO network_public_ip(id,tenant_id,address,subnet,gateway,vendor_id,notes,block_id,address_role)
                SELECT gen_random_uuid(),?,?::inet+n,?,?,?,?,?,CASE WHEN ? >= 31 THEN 'HOST' WHEN n=0 THEN 'NETWORK'
                    WHEN n=? THEN 'BROADCAST' ELSE 'HOST' END FROM generate_series(0,?) n
                ON CONFLICT(tenant_id,address) DO UPDATE SET block_id=excluded.block_id,address_role=excluded.address_role,
                    revision=network_public_ip.revision+1""", sql.tenant, Ipv4Block.address(block.start), block.subnet,
                value.gateway, value.vendorId, value.notes, id, block.prefix, block.size-1, block.size-1)
            get(sql, id)
        }
    }
    fun monitoring(id: UUID, input: IpBlockMonitoringInput, key: UUID): IpBlockView {
        val actor = inventory.access(true)
        return inventory.mutate(actor, key, "ip-block-monitoring", id, input, IpBlockView::class.java) { sql ->
            val prior = get(sql,id)
            if (input.expectedRevision != prior.revision) throw ConflictException("Blok berubah. Muat ulang sebelum menyimpan")
            sql.update("UPDATE network_ip_block SET monitoring_enabled=?,next_check_at=now(),revision=revision+1 WHERE tenant_id=? AND id=?",
                input.enabled, sql.tenant, id)
            get(sql,id)
        }
    }
    private fun preview(sql: B2BSql, block: Ipv4Block) = IpBlockPreview(block.cidr,block.subnet,Ipv4Block.address(block.start),
        Ipv4Block.address(block.end),block.size,block.hosts,sql.query("SELECT count(*) FROM network_public_ip WHERE tenant_id=? AND address <<= ?::cidr",
            sql.tenant,block.cidr) { it.getInt(1) }.single())
    private fun validate(sql: B2BSql, input: IpBlockInput, block: Ipv4Block) {
        network.ipv4(input.gateway)
        if (input.notes.length > 2000) throw ValidationException("Catatan maksimal 2000 karakter")
        network.activeVendor(sql,input.vendorId)
        if (sql.query("SELECT id FROM network_ip_block WHERE tenant_id=? AND network && ?::cidr",sql.tenant,block.cidr) { true }.isNotEmpty())
            throw ConflictException("Subnet bertumpuk dengan blok yang sudah terdaftar")
        if (sql.query("SELECT id FROM network_public_ip WHERE tenant_id=? AND address <<= ?::cidr AND vendor_id IS DISTINCT FROM ?::uuid",
                sql.tenant,block.cidr,input.vendorId) { true }.isNotEmpty()) throw ConflictException("Vendor IP lama berbeda dari vendor blok")
        if (block.prefix < 31 && sql.query("""SELECT p.id FROM network_public_ip p JOIN network_ip_allocation a
            ON a.tenant_id=p.tenant_id AND a.ip_id=p.id WHERE p.tenant_id=? AND p.address IN (?::inet,?::inet)""",
                sql.tenant,Ipv4Block.address(block.start),Ipv4Block.address(block.end)) { true }.isNotEmpty())
            throw ConflictException("Alamat network atau broadcast sudah dipakai")
    }
    private fun get(sql: B2BSql,id: UUID): IpBlockView = sql.query("$SELECT WHERE b.tenant_id=? AND b.id=?",sql.tenant,id,map=::blockView)
        .singleOrNull() ?: throw NotFoundException("Blok IP tidak ditemukan")
    private fun blockView(row: java.sql.ResultSet): IpBlockView {
        val block = Ipv4Block.parse(row.getString("cidr"))
        return IpBlockView(row.uuid("id"),block.cidr,row.getObject("vendor_id",UUID::class.java),row.getString("vendor_name"),
            row.getString("gateway"),row.getString("notes"),row.getBoolean("monitoring_enabled"),row.getInt("interval_seconds"),
            row.getLong("revision"),block.size,block.hosts)
    }
    companion object {
        private const val SELECT = """SELECT b.*,b.network::text cidr,v.name vendor_name FROM network_ip_block b
            LEFT JOIN network_vendor v ON v.tenant_id=b.tenant_id AND v.id=b.vendor_id"""
    }
}

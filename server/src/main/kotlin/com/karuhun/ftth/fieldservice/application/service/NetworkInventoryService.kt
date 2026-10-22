package com.karuhun.ftth.fieldservice.application.service

import com.karuhun.ftth.common.domain.PageRequest
import com.karuhun.ftth.common.domain.error.*
import com.karuhun.ftth.common.security.ReadOnlyLockGuard
import com.karuhun.ftth.fieldservice.adapter.outbound.persistence.*
import com.karuhun.ftth.iam.CurrentAuthorityApi
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.security.MessageDigest
import java.util.UUID

@Service
@Transactional
class NetworkInventoryService(private val store: B2BStore, private val authority: CurrentAuthorityApi,
    private val network: B2BNetworkService, private val readOnly: ObjectProvider<ReadOnlyLockGuard>) {
    private val mapper = jacksonObjectMapper()
    private fun access(write: Boolean): UUID {
        val actor = authority.lockCurrent().also { it.fence.assertHeld() }
        val permission = if (write) "network.ip.manage" else "network.ip.view"
        if (!actor.platformAdmin && permission !in actor.permissions) throw AccessDeniedException("Tidak memiliki izin manajemen IP")
        if (write && readOnly.getIfAvailable()?.isReadOnly() == true) throw SubscriptionLockedException()
        return actor.fence.identity.userId
    }
    fun vendors(q: String, active: Boolean?, page: PageRequest) = access(false).let {
        store.use { sql -> sql.page("""SELECT * FROM network_vendor WHERE tenant_id=? AND lower(name) LIKE ?
            AND (?::boolean IS NULL OR active=?) ORDER BY lower(name),id""", page, sql.tenant,
            "%" + q.trim().lowercase().take(200) + "%", active, active, map=::networkVendor) }
    }
    fun vendor(id: UUID): NetworkVendorView {
        access(false)
        return store.use { it.vendor(id) }
    }
    fun ips(q: String, status: String?, vendor: UUID?, page: PageRequest) = access(false).let {
        if (status != null && status !in setOf("AVAILABLE", "ALLOCATED")) throw ValidationException("Filter status IP tidak valid")
        store.use { sql -> sql.page("""$PUBLIC_IP_SELECT WHERE p.tenant_id=?
            AND (host(p.address) LIKE ? OR lower(coalesce(s.name,'')) LIKE ?)
            AND (?::text IS NULL OR (?='AVAILABLE' AND a.client_id IS NULL) OR (?='ALLOCATED' AND a.client_id IS NOT NULL))
            AND (?::uuid IS NULL OR p.vendor_id=?) ORDER BY p.address,p.id""", page, sql.tenant,
            "%" + q.trim().lowercase().take(200) + "%", "%" + q.trim().lowercase().take(200) + "%",
            status, status, status, vendor, vendor, map=::publicIp) }
    }
    fun clientNetwork(id: UUID): B2BNetworkView? {
        access(false)
        return store.use { sql ->
            if (!sql.exists(id)) throw NotFoundException("Client B2B tidak ditemukan")
            sql.network(id)
        }
    }
    fun saveVendor(id: UUID?, input: NetworkVendorInput, key: UUID): NetworkVendorView {
        val actor = access(true)
        val value = input.copy(name=input.name.trim(), contact=input.contact.trim(), notes=input.notes.trim())
        if (value.name.length !in 2..200 || value.contact.length > 300 || value.notes.length > 2000 || value.expectedRevision < 0)
            throw ValidationException("Isi nama vendor 2 sampai 200 karakter; kontak maksimal 300 dan catatan 2000")
        return mutate(actor, key, "vendor", id, value, NetworkVendorView::class.java) { sql ->
            val target = id ?: UUID.randomUUID()
            val previous = id?.let(sql::vendor)
            revision(previous?.revision, value.expectedRevision)
            if (sql.query("SELECT id FROM network_vendor WHERE tenant_id=? AND lower(name)=lower(?) AND id<>?",
                    sql.tenant, value.name, target) { true }.isNotEmpty()) throw ConflictException("Nama vendor sudah ada")
            sql.update("""INSERT INTO network_vendor(id,tenant_id,name,contact,notes,active) VALUES (?,?,?,?,?,?)
                ON CONFLICT(id) DO UPDATE SET name=excluded.name,contact=excluded.contact,notes=excluded.notes,
                active=excluded.active,revision=network_vendor.revision+1""", target, sql.tenant, value.name, value.contact, value.notes, value.active)
            sql.vendor(target)
        }
    }
    fun saveIp(id: UUID?, input: PublicIpInput, key: UUID): PublicIpView {
        val actor = access(true)
        val value = input.copy(address=network.ipv4(input.address, true), subnet=network.subnet(input.subnet),
            gateway=network.ipv4(input.gateway), notes=input.notes.trim())
        if (value.notes.length > 2000 || value.expectedRevision < 0) throw ValidationException("Catatan maksimal 2000 karakter dan revisi tidak boleh negatif")
        return mutate(actor, key, "ip", id, value, PublicIpView::class.java) { sql ->
            val target = id ?: UUID.randomUUID()
            val previous = id?.let(sql::ip)
            revision(previous?.revision, value.expectedRevision)
            network.activeVendor(sql, value.vendorId, previous?.vendorId)
            if (previous?.clientId != null && (previous.address != value.address || previous.vendorId != value.vendorId))
                throw ConflictException("Lepaskan alokasi pelanggan sebelum mengubah IP atau vendor")
            if (sql.query("SELECT id FROM network_public_ip WHERE tenant_id=? AND address=?::inet AND id<>?",
                    sql.tenant, value.address, target) { true }.isNotEmpty()) throw ConflictException("IP publik sudah terdaftar")
            sql.update("""INSERT INTO network_public_ip(id,tenant_id,address,subnet,gateway,vendor_id,notes) VALUES (?,?,?::inet,?,?,?,?)
                ON CONFLICT(id) DO UPDATE SET address=excluded.address,subnet=excluded.subnet,gateway=excluded.gateway,
                vendor_id=excluded.vendor_id,notes=excluded.notes,revision=network_public_ip.revision+1""", target, sql.tenant,
                value.address, value.subnet, value.gateway, value.vendorId, value.notes)
            sql.ip(target)
        }
    }
    fun deleteVendor(id: UUID, expectedRevision: Long) {
        access(true)
        store.use { sql ->
            sql.lock(); revision(sql.vendor(id).revision, expectedRevision)
            if (sql.query("""SELECT 1 FROM network_public_ip WHERE tenant_id=? AND vendor_id=? UNION ALL
                SELECT 1 FROM b2b_client_network WHERE tenant_id=? AND (main_vendor_id=? OR backup_vendor_id=?) LIMIT 1""",
                    sql.tenant, id, sql.tenant, id, id) { true }.isNotEmpty()) throw ConflictException("Vendor masih dipakai. Nonaktifkan atau pindahkan data terlebih dahulu")
            sql.update("DELETE FROM network_vendor WHERE tenant_id=? AND id=?", sql.tenant, id)
        }
    }
    fun deleteIp(id: UUID, expectedRevision: Long) {
        access(true)
        store.use { sql ->
            sql.lock(); val ip = sql.ip(id); revision(ip.revision, expectedRevision)
            if (ip.clientId != null) throw ConflictException("IP masih dialokasikan ke " + ip.clientName)
            sql.update("DELETE FROM network_public_ip WHERE tenant_id=? AND id=?", sql.tenant, id)
        }
    }
    private fun revision(previous: Long?, expected: Long) {
        if (expected < 0 || (previous == null && expected != 0L)) throw ValidationException("Revisi data baru harus nol")
        if (previous != null && previous != expected) throw ConflictException("Data berubah. Muat ulang sebelum menyimpan")
    }
    private fun <T> mutate(actor: UUID, key: UUID, kind: String, id: UUID?, input: Any, type: Class<T>, action: (B2BSql) -> T): T {
        val hash = MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsBytes(mapOf("kind" to kind, "id" to id, "input" to input)))
            .joinToString("") { "%02x".format(it) }
        return store.use { sql ->
            sql.lock()
            sql.query("SELECT actor_id,payload_hash,result::text FROM network_command WHERE tenant_id=? AND operation_key=?", sql.tenant, key) {
                Triple(it.uuid("actor_id"), it.getString("payload_hash"), it.getString("result"))
            }.singleOrNull()?.let {
                if (it.first != actor || it.second != hash) throw ConflictException("Kunci pengiriman sudah dipakai untuk perubahan lain")
                return@use mapper.readValue(it.third, type)
            }
            val result = action(sql)
            sql.update("INSERT INTO network_command(tenant_id,operation_key,actor_id,payload_hash,result) VALUES (?,?,?,?,?::jsonb)",
                sql.tenant, key, actor, hash, mapper.writeValueAsString(result))
            result
        }
    }
}

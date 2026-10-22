package com.karuhun.ftth.fieldservice.application.service

import com.karuhun.ftth.common.domain.error.ConflictException
import com.karuhun.ftth.common.domain.error.ValidationException
import com.karuhun.ftth.common.security.SecretCipher
import com.karuhun.ftth.fieldservice.adapter.outbound.persistence.*
import org.springframework.stereotype.Service
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.util.UUID

@Service
class B2BNetworkService(private val cipher: SecretCipher) {
    private val mapper = jacksonObjectMapper()

    fun normalize(input: B2BNetworkInput): B2BNetworkInput {
        val d = input.details
        val details = d.copy(picName=d.picName.trim(), phone=d.phone.trim(), mainLastMile=d.mainLastMile.trim(),
            mainCustomerId=d.mainCustomerId.trim(), backupCustomerId=d.backupCustomerId.trim(), subnet=subnet(d.subnet),
            gateway=ipv4(d.gateway), dns=d.dns.map { ipv4(it, required=true) }.distinct(), tunnelDial=ipv4(d.tunnelDial),
            tunnelUsername=d.tunnelUsername.trim(), tunnelRemoteAddress=ipv4(d.tunnelRemoteAddress),
            tunnelLocalAddress=ipv4(d.tunnelLocalAddress), notes=d.notes.trim())
        if (details.picName.length > 200 || details.phone.length > 50 || details.mainLastMile.length > 200 ||
            details.mainCustomerId.length > 200 || details.backupCustomerId.length > 200 || details.tunnelUsername.length > 200 ||
            details.notes.length > 5000) throw ValidationException("Data jaringan melebihi batas karakter")
        if (details.mainInfrastructure !in setOf("OWN", "PARTNER")) throw ValidationException("Pilih infrastruktur sendiri atau mitra")
        if (details.mainInfrastructure == "PARTNER" && details.mainVendorId == null) throw ValidationException("Pilih vendor mainlink untuk infrastruktur mitra")
        if (details.mainInfrastructure == "OWN" && details.mainVendorId != null) throw ValidationException("Infrastruktur sendiri tidak memakai vendor mainlink")
        listOf(details.mainBandwidthMbps, details.backupBandwidthMbps).forEach {
            if (it != null && it !in 1..1_000_000) throw ValidationException("Bandwidth harus 1 sampai 1000000 Mbps")
        }
        if (input.publicIpIds.size > 64 || input.publicIpIds.distinct().size != input.publicIpIds.size || d.dns.size > 8)
            throw ValidationException("Pilih maksimal 64 IP berbeda dan 8 DNS")
        if (input.tunnelPassword != null && input.tunnelPassword.length !in 1..500)
            throw ValidationException("Password tunnel harus 1 sampai 500 karakter")
        if (input.clearTunnelPassword && input.tunnelPassword != null) throw ValidationException("Pilih ganti atau hapus password tunnel")
        return input.copy(details=details, publicIpIds=input.publicIpIds.sortedBy(UUID::toString))
    }

    fun save(sql: B2BSql, client: UUID, input: B2BNetworkInput) {
        val previous = sql.network(client)
        activeVendor(sql, input.details.mainVendorId, previous?.details?.mainVendorId)
        activeVendor(sql, input.details.backupVendorId, previous?.details?.backupVendorId)
        input.publicIpIds.forEach { id ->
            val ip = sql.ip(id)
            if (ip.clientId != null && ip.clientId != client) throw ConflictException("IP " + ip.address + " sudah dipakai " + ip.clientName)
        }
        val password = when {
            input.clearTunnelPassword -> null
            input.tunnelPassword != null -> cipher.encrypt(input.tunnelPassword)
            else -> sql.query("SELECT tunnel_password_ciphertext FROM b2b_client_network WHERE tenant_id=? AND client_id=?",
                sql.tenant, client) { it.getString(1) }.singleOrNull()
        }
        sql.update("""INSERT INTO b2b_client_network(tenant_id,client_id,details,main_vendor_id,backup_vendor_id,tunnel_password_ciphertext)
            VALUES (?,?,?::jsonb,?,?,?) ON CONFLICT(tenant_id,client_id) DO UPDATE SET details=excluded.details,
            main_vendor_id=excluded.main_vendor_id,backup_vendor_id=excluded.backup_vendor_id,
            tunnel_password_ciphertext=excluded.tunnel_password_ciphertext""", sql.tenant, client,
            mapper.writeValueAsString(input.details), input.details.mainVendorId, input.details.backupVendorId, password)
        sql.update("DELETE FROM network_ip_allocation WHERE tenant_id=? AND client_id=?", sql.tenant, client)
        input.publicIpIds.forEach { sql.update("INSERT INTO network_ip_allocation(tenant_id,ip_id,client_id) VALUES (?,?,?)", sql.tenant, it, client) }
    }

    internal fun activeVendor(sql: B2BSql, id: UUID?, previous: UUID? = null) {
        if (id != null && !sql.vendor(id).active && id != previous) throw ValidationException("Vendor tidak aktif. Pilih vendor aktif")
    }

    internal fun ipv4(raw: String, required: Boolean = false): String {
        val value = raw.trim()
        if (value.isEmpty() && !required) return value
        val parts = value.split('.')
        if (parts.size != 4 || parts.any { !it.matches(Regex("0|[1-9][0-9]{0,2}")) || it.toInt() > 255 })
            throw ValidationException("Gunakan alamat IPv4, misalnya 157.15.122.100")
        return value
    }

    internal fun subnet(raw: String): String {
        val value = ipv4(raw)
        if (value.isEmpty()) return value
        val mask = value.split('.').fold(0L) { acc, part -> (acc shl 8) or part.toLong() }
        val inverse = mask xor 0xffffffffL
        if ((inverse and (inverse + 1)) != 0L) throw ValidationException("Subnet mask harus berurutan, misalnya 255.255.255.248")
        return value
    }
}

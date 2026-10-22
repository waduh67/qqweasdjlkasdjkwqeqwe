package com.karuhun.ftth.fieldservice.adapter.outbound.persistence

import com.karuhun.ftth.common.domain.error.NotFoundException
import com.karuhun.ftth.fieldservice.application.service.*
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.sql.ResultSet
import java.util.UUID

internal const val PUBLIC_IP_SELECT = """SELECT p.*,host(p.address) ip_address,v.name vendor_name,a.client_id,s.name client_name
    FROM network_public_ip p LEFT JOIN network_vendor v ON v.tenant_id=p.tenant_id AND v.id=p.vendor_id
    LEFT JOIN network_ip_allocation a ON a.tenant_id=p.tenant_id AND a.ip_id=p.id
    LEFT JOIN LATERAL (SELECT name FROM b2b_client_setting WHERE tenant_id=a.tenant_id AND client_id=a.client_id
        ORDER BY effective_month DESC LIMIT 1) s ON true"""
internal fun networkVendor(row: ResultSet) = NetworkVendorView(row.uuid("id"), row.getString("name"),
    row.getString("contact"), row.getString("notes"), row.getBoolean("active"), row.getLong("revision"))
internal fun publicIp(row: ResultSet) = PublicIpView(row.uuid("id"), row.getString("ip_address"), row.getString("subnet"),
    row.getString("gateway"), row.getObject("vendor_id", UUID::class.java), row.getString("vendor_name"),
    row.getString("notes"), row.getLong("revision"), row.getObject("client_id", UUID::class.java), row.getString("client_name"))
internal fun B2BSql.vendor(id: UUID): NetworkVendorView = query("SELECT * FROM network_vendor WHERE tenant_id=? AND id=?",
    tenant, id, map=::networkVendor).singleOrNull() ?: throw NotFoundException("Vendor tidak ditemukan")
internal fun B2BSql.ip(id: UUID): PublicIpView = query("$PUBLIC_IP_SELECT WHERE p.tenant_id=? AND p.id=?",
    tenant, id, map=::publicIp).singleOrNull() ?: throw NotFoundException("IP publik tidak ditemukan")
internal fun B2BSql.network(id: UUID): B2BNetworkView? = query(
    "SELECT details::text,tunnel_password_ciphertext IS NOT NULL password_set FROM b2b_client_network WHERE tenant_id=? AND client_id=?",
    tenant, id) { jacksonObjectMapper().readValue(it.getString("details"), B2BNetworkDetails::class.java) to it.getBoolean("password_set") }
    .singleOrNull()?.let { (details, passwordSet) ->
        B2BNetworkView(details, query("$PUBLIC_IP_SELECT WHERE p.tenant_id=? AND a.client_id=? ORDER BY p.address", tenant, id, map=::publicIp), passwordSet)
    }

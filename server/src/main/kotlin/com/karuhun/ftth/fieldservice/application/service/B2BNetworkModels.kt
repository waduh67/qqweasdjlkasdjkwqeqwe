package com.karuhun.ftth.fieldservice.application.service

import java.util.UUID

data class NetworkVendorInput(val name: String, val contact: String = "", val notes: String = "",
    val active: Boolean = true, val expectedRevision: Long = 0)
data class NetworkVendorView(val id: UUID, val name: String, val contact: String, val notes: String,
    val active: Boolean, val revision: Long)
data class PublicIpInput(val address: String, val subnet: String = "", val gateway: String = "",
    val vendorId: UUID? = null, val notes: String = "", val expectedRevision: Long = 0, val usedBy: String? = null)
data class PublicIpView(val id: UUID, val address: String, val subnet: String, val gateway: String,
    val vendorId: UUID?, val vendorName: String?, val notes: String, val revision: Long,
    val clientId: UUID?, val clientName: String?, val blockId: UUID? = null, val addressRole: String = "HOST",
    val usedBy: String = "", val probe: IpProbeResult? = null)

data class IpProbeResult(val status: String, val checkedAt: java.time.Instant, val rttMillis: Double? = null,
    val error: String? = null)
data class IpBlockInput(val cidr: String, val vendorId: UUID? = null, val gateway: String = "", val notes: String = "")
data class IpBlockPreview(val cidr: String, val subnet: String, val firstAddress: String, val lastAddress: String,
    val addressCount: Int, val hostCount: Int, val existingCount: Int)
data class IpBlockView(val id: UUID, val cidr: String, val vendorId: UUID?, val vendorName: String?, val gateway: String,
    val notes: String, val monitoringEnabled: Boolean, val intervalSeconds: Int, val revision: Long, val addressCount: Int, val hostCount: Int)
data class IpBlockMonitoringInput(val expectedRevision: Long, val enabled: Boolean)
data class IpProbeJobView(val id: UUID, val blockId: UUID?, val state: String, val total: Int, val completed: Int,
    val up: Int, val down: Int, val errors: Int, val createdAt: java.time.Instant)
data class B2BNetworkDetails(val picName: String = "", val phone: String = "",
    val mainBandwidthMbps: Int? = null, val mainInfrastructure: String = "OWN", val mainLastMile: String = "",
    val mainVendorId: UUID? = null, val mainCustomerId: String = "", val backupBandwidthMbps: Int? = null,
    val backupVendorId: UUID? = null, val backupCustomerId: String = "", val subnet: String = "", val gateway: String = "",
    val dns: List<String> = emptyList(), val tunnelDial: String = "", val tunnelUsername: String = "",
    val tunnelRemoteAddress: String = "", val tunnelLocalAddress: String = "", val notes: String = "")
data class B2BNetworkInput(val details: B2BNetworkDetails = B2BNetworkDetails(), val publicIpIds: List<UUID> = emptyList(),
    val tunnelPassword: String? = null, val clearTunnelPassword: Boolean = false)
data class B2BNetworkView(val details: B2BNetworkDetails, val publicIps: List<PublicIpView>, val tunnelPasswordSet: Boolean)

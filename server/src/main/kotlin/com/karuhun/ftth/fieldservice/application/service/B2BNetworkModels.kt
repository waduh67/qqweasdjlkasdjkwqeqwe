package com.karuhun.ftth.fieldservice.application.service

import java.util.UUID

data class NetworkVendorInput(val name: String, val contact: String = "", val notes: String = "",
    val active: Boolean = true, val expectedRevision: Long = 0)
data class NetworkVendorView(val id: UUID, val name: String, val contact: String, val notes: String,
    val active: Boolean, val revision: Long)
data class PublicIpInput(val address: String, val subnet: String = "", val gateway: String = "",
    val vendorId: UUID? = null, val notes: String = "", val expectedRevision: Long = 0)
data class PublicIpView(val id: UUID, val address: String, val subnet: String, val gateway: String,
    val vendorId: UUID?, val vendorName: String?, val notes: String, val revision: Long,
    val clientId: UUID?, val clientName: String?)
data class B2BNetworkDetails(val picName: String = "", val phone: String = "",
    val mainBandwidthMbps: Int? = null, val mainInfrastructure: String = "OWN", val mainLastMile: String = "",
    val mainVendorId: UUID? = null, val mainCustomerId: String = "", val backupBandwidthMbps: Int? = null,
    val backupVendorId: UUID? = null, val backupCustomerId: String = "", val subnet: String = "", val gateway: String = "",
    val dns: List<String> = emptyList(), val tunnelDial: String = "", val tunnelUsername: String = "",
    val tunnelRemoteAddress: String = "", val tunnelLocalAddress: String = "", val notes: String = "")
data class B2BNetworkInput(val details: B2BNetworkDetails = B2BNetworkDetails(), val publicIpIds: List<UUID> = emptyList(),
    val tunnelPassword: String? = null, val clearTunnelPassword: Boolean = false)
data class B2BNetworkView(val details: B2BNetworkDetails, val publicIps: List<PublicIpView>, val tunnelPasswordSet: Boolean)

package com.duluin.ftth.network.application.port.inbound

import com.duluin.ftth.common.domain.geo.Coordinate
import com.duluin.ftth.common.domain.geo.RoutePath
import com.duluin.ftth.network.domain.model.AssetStatus
import com.duluin.ftth.network.domain.model.CableType
import com.duluin.ftth.network.domain.model.NetworkNodeKind
import com.duluin.ftth.network.domain.model.OltVendor
import com.duluin.ftth.network.domain.model.SnmpVersion
import com.duluin.ftth.network.domain.model.WebProtocol
import java.util.UUID

/**
 * Bentuk baca (read model) yang dikembalikan use case ke lapisan web.
 *
 * Sengaja dipisahkan dari agregat domain: agregat boleh berubah bentuk mengikuti
 * kebutuhan invariant, sedangkan view adalah kontrak yang dilihat klien.
 */

data class SiteView(
    val id: UUID,
    val code: String,
    val name: String,
    val address: String?,
    val location: Coordinate,
    val areaId: UUID?,
    val oltCount: Long,
)

data class OltView(
    val id: UUID,
    val code: String,
    val name: String,
    val siteId: UUID,
    val siteName: String?,
    val vendor: OltVendor,
    val model: String?,
    val managementIp: String?,
    val status: AssetStatus,
    /** Community string TIDAK pernah dikembalikan — hanya penanda ada/tidaknya. */
    val snmpConfigured: Boolean,
    val snmpPort: Int,
    val pollable: Boolean,
    val ponPortCount: Int,
    val location: Coordinate,
    val areaId: UUID?,
    val description: String?,
    val snmpEnabled: Boolean,
    val snmpVersion: SnmpVersion,
    val webEnabled: Boolean,
    val webProtocol: WebProtocol,
    val webPort: Int?,
    val webUsername: String?,
    /** Password Web TIDAK pernah dikembalikan — hanya penanda ada/tidaknya. */
    val webPasswordConfigured: Boolean,
)

data class PonPortView(
    val id: UUID,
    val oltId: UUID,
    val label: String,
    val description: String?,
    val status: AssetStatus,
    val odcCount: Long,
)

data class OdcView(
    val id: UUID,
    val code: String,
    val name: String,
    val address: String?,
    val location: Coordinate,
    val areaId: UUID?,
    val ponPortId: UUID?,
    val ponPortLabel: String?,
    val oltName: String?,
    val splitterRatio: String,
    val capacity: Int,
    val odpCount: Long,
    val status: AssetStatus,
    val energized: Boolean,
)

data class OdpView(
    val id: UUID,
    val code: String,
    val name: String,
    val address: String?,
    val location: Coordinate,
    val areaId: UUID?,
    val odcId: UUID?,
    val odcName: String?,
    val splitterRatio: String,
    val capacity: Int,
    val status: AssetStatus,
)

data class CableView(
    val id: UUID,
    val code: String,
    val name: String,
    val cableType: CableType,
    val coreCount: Int,
    val route: RoutePath,
    val lengthMeters: Double,
    val fromKind: NetworkNodeKind,
    val fromId: UUID,
    val toKind: NetworkNodeKind,
    val toId: UUID,
    val status: AssetStatus,
)

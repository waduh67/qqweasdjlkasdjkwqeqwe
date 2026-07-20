package com.duluin.ftth.network.adapter.inbound.web

import com.duluin.ftth.common.domain.geo.Coordinate
import com.duluin.ftth.network.domain.model.AssetStatus
import com.duluin.ftth.network.domain.model.CableType
import com.duluin.ftth.network.domain.model.NetworkNodeKind
import com.duluin.ftth.network.domain.model.OltVendor
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.Size
import java.util.UUID

/**
 * DTO request untuk module network.
 *
 * Bean Validation di sini hanya menyaring bentuk yang jelas salah (kosong,
 * kepanjangan, di luar rentang) supaya klien dapat pesan cepat. Aturan bisnis
 * sesungguhnya tetap ditegakkan agregat domain — validasi di web bersifat
 * tambahan, bukan pengganti.
 */

data class LocationRequest(
    @field:Min(-180) @field:Max(180) val longitude: Double,
    @field:Min(-90) @field:Max(90) val latitude: Double,
) {
    fun toCoordinate(): Coordinate = Coordinate(longitude, latitude)
}

data class SiteRequest(
    @field:NotBlank @field:Size(max = 40) val code: String,
    @field:NotBlank @field:Size(max = 150) val name: String,
    @field:Size(max = 500) val address: String? = null,
    @field:Valid val location: LocationRequest,
    val areaId: UUID? = null,
)

data class OltRequest(
    val siteId: UUID,
    @field:NotBlank @field:Size(max = 40) val code: String,
    @field:NotBlank @field:Size(max = 150) val name: String,
    val vendor: OltVendor,
    @field:Size(max = 80) val model: String? = null,
    @field:Size(max = 45) val managementIp: String? = null,
    /** Kosongkan untuk mempertahankan community string yang sudah tersimpan. */
    val snmpCommunity: String? = null,
)

data class PonPortRequest(
    @field:NotBlank @field:Size(max = 30) val label: String,
    @field:Size(max = 255) val description: String? = null,
    val status: AssetStatus = AssetStatus.ACTIVE,
)

data class OdcRequest(
    @field:NotBlank @field:Size(max = 40) val code: String,
    @field:NotBlank @field:Size(max = 150) val name: String,
    @field:Size(max = 500) val address: String? = null,
    @field:Valid val location: LocationRequest,
    val areaId: UUID? = null,
    val ponPortId: UUID? = null,
    @field:NotBlank val splitterRatio: String,
    @field:Min(1) @field:Max(1024) val capacity: Int,
    val status: AssetStatus = AssetStatus.ACTIVE,
)

data class OdpRequest(
    @field:NotBlank @field:Size(max = 40) val code: String,
    @field:NotBlank @field:Size(max = 150) val name: String,
    @field:Size(max = 500) val address: String? = null,
    @field:Valid val location: LocationRequest,
    val areaId: UUID? = null,
    val odcId: UUID? = null,
    @field:NotBlank val splitterRatio: String,
    @field:Min(1) @field:Max(256) val capacity: Int,
    val status: AssetStatus = AssetStatus.ACTIVE,
)

data class CableRequest(
    @field:NotBlank @field:Size(max = 40) val code: String,
    @field:NotBlank @field:Size(max = 150) val name: String,
    val cableType: CableType,
    @field:Min(1) @field:Max(288) val coreCount: Int,
    @field:NotEmpty @field:Size(min = 2, max = 2000) @field:Valid val route: List<LocationRequest>,
    val fromKind: NetworkNodeKind,
    val fromId: UUID,
    val toKind: NetworkNodeKind,
    val toId: UUID,
    val status: AssetStatus = AssetStatus.ACTIVE,
)

/** Badan request untuk operasi sambung/lepas; `null` berarti melepas sambungan. */
data class ConnectRequest(val targetId: UUID? = null)

data class StatusRequest(val status: AssetStatus)

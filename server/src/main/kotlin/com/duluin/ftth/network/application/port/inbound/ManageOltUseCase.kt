package com.duluin.ftth.network.application.port.inbound

import com.duluin.ftth.common.domain.Page
import com.duluin.ftth.common.domain.PageRequest
import com.duluin.ftth.common.domain.geo.Coordinate
import com.duluin.ftth.network.domain.model.AssetStatus
import com.duluin.ftth.network.domain.model.OltVendor
import com.duluin.ftth.network.domain.model.SnmpVersion
import com.duluin.ftth.network.domain.model.WebProtocol
import java.util.UUID

interface ManageOltUseCase {

    fun search(query: String, siteId: UUID?, pageRequest: PageRequest): Page<OltView>

    fun get(id: UUID): OltView

    fun create(command: SaveOltCommand): OltView

    fun update(id: UUID, command: SaveOltCommand): OltView

    /** Memindah titik OLT di peta; ujung kabel yang menyentuhnya ikut menempel ulang. */
    fun relocate(id: UUID, location: Coordinate): OltView

    fun changeStatus(id: UUID, status: AssetStatus): OltView

    fun delete(id: UUID)

    fun listPonPorts(oltId: UUID): List<PonPortView>

    fun addPonPort(oltId: UUID, command: SavePonPortCommand): PonPortView

    fun updatePonPort(id: UUID, command: SavePonPortCommand): PonPortView

    fun deletePonPort(id: UUID)
}

data class SaveOltCommand(
    val siteId: UUID,
    val code: String,
    val name: String,
    val vendor: OltVendor,
    val model: String?,
    val managementIp: String?,
    /**
     * `null` berarti biarkan kredensial yang tersimpan apa adanya — perlu karena
     * API tidak pernah mengembalikan nilai lama, sehingga klien yang mengirim
     * ulang form tanpa mengisi ulang password tidak boleh menghapusnya.
     */
    val snmpCommunity: String?,
    val snmpPort: Int = 161,
    /**
     * Titik OLT di peta. `null` berarti "warisi dari lokasi site-nya" — dipakai
     * jalur pembuatan yang belum menaruh OLT sendiri (mis. tab inventaris tanpa
     * peta); jalur "taruh di peta" mengirim koordinat eksplisit.
     */
    val location: Coordinate? = null,
    val description: String? = null,
    val snmpEnabled: Boolean = true,
    val snmpVersion: SnmpVersion = SnmpVersion.V2C,
    val webEnabled: Boolean = false,
    val webProtocol: WebProtocol = WebProtocol.HTTP,
    val webPort: Int? = null,
    val webUsername: String? = null,
    /** `null` = pertahankan password Web tersimpan (API tak pernah mengembalikannya). */
    val webPassword: String? = null,
)

data class SavePonPortCommand(
    val label: String,
    val description: String?,
    val status: AssetStatus,
)

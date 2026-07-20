package com.duluin.ftth.network.domain.model

import com.duluin.ftth.common.domain.UuidV7
import com.duluin.ftth.common.domain.error.ValidationException
import com.duluin.ftth.network.domain.model.vo.ManagementIp
import java.util.UUID

/**
 * Optical Line Terminal — perangkat aktif di sisi ISP yang melayani ratusan
 * hingga ribuan ONU lewat PON port-nya.
 *
 * [snmpCommunity] dipegang di sini sebagai plaintext; enkripsi saat menyimpan
 * adalah urusan adapter persistence, bukan model domain.
 */
class Olt private constructor(
    val id: UUID,
    val tenantId: UUID,
    val code: String,
    siteId: UUID,
    name: String,
    vendor: OltVendor,
    model: String?,
    managementIp: ManagementIp?,
    snmpCommunity: String?,
    status: AssetStatus,
) {
    var siteId: UUID = siteId
        private set

    var name: String = name
        private set

    var vendor: OltVendor = vendor
        private set

    var model: String? = model
        private set

    var managementIp: ManagementIp? = managementIp
        private set

    var snmpCommunity: String? = snmpCommunity
        private set

    var status: AssetStatus = status
        private set

    fun update(siteId: UUID, name: String, vendor: OltVendor, model: String?, managementIp: ManagementIp?) {
        this.siteId = siteId
        this.name = AssetNaming.name(name, "OLT")
        this.vendor = vendor
        this.model = model?.trim()?.takeIf { it.isNotEmpty() }
        this.managementIp = managementIp
    }

    /** `null` berarti "jangan ubah"; string kosong berarti "hapus kredensial". */
    fun changeSnmpCommunity(community: String?) {
        if (community == null) return
        snmpCommunity = community.trim().takeIf { it.isNotEmpty() }
    }

    fun changeStatus(status: AssetStatus) {
        this.status = status
    }

    /**
     * Collector hanya bisa mem-polling OLT yang vendornya didukung sekaligus
     * punya alamat dan kredensial. Dipakai UI untuk menandai OLT yang
     * "terinventarisasi tapi belum termonitor".
     */
    fun isPollable(): Boolean =
        vendor.monitoringSupported() && managementIp != null && !snmpCommunity.isNullOrBlank()

    companion object {
        fun create(
            tenantId: UUID,
            siteId: UUID,
            code: String,
            name: String,
            vendor: OltVendor,
            model: String?,
            managementIp: ManagementIp?,
            snmpCommunity: String?,
            status: AssetStatus = AssetStatus.ACTIVE,
        ): Olt = Olt(
            id = UuidV7.generate(),
            tenantId = tenantId,
            code = AssetNaming.code(code, "OLT"),
            siteId = siteId,
            name = AssetNaming.name(name, "OLT"),
            vendor = vendor,
            model = model?.trim()?.takeIf { it.isNotEmpty() },
            managementIp = managementIp,
            snmpCommunity = snmpCommunity?.trim()?.takeIf { it.isNotEmpty() },
            status = status,
        )

        fun rehydrate(
            id: UUID,
            tenantId: UUID,
            siteId: UUID,
            code: String,
            name: String,
            vendor: OltVendor,
            model: String?,
            managementIp: ManagementIp?,
            snmpCommunity: String?,
            status: AssetStatus,
        ): Olt = Olt(id, tenantId, code, siteId, name, vendor, model, managementIp, snmpCommunity, status)
    }
}

/**
 * Satu port PON pada OLT. Menjadi titik sambung feeder ke ODC — sebuah ODC
 * "menggantung" pada tepat satu PON port.
 */
class PonPort private constructor(
    val id: UUID,
    val tenantId: UUID,
    val oltId: UUID,
    label: String,
    description: String?,
    status: AssetStatus,
) {
    var label: String = label
        private set

    var description: String? = description
        private set

    var status: AssetStatus = status
        private set

    fun update(label: String, description: String?, status: AssetStatus) {
        this.label = validateLabel(label)
        this.description = description?.trim()?.takeIf { it.isNotEmpty() }
        this.status = status
    }

    companion object {
        /** Notasi lapangan frame/slot/port, mis. `1/2/3` atau `0/1`. */
        private val LABEL_PATTERN = Regex("""^\d{1,3}(/\d{1,3}){1,3}$""")

        fun create(
            tenantId: UUID,
            oltId: UUID,
            label: String,
            description: String? = null,
            status: AssetStatus = AssetStatus.ACTIVE,
        ): PonPort = PonPort(
            id = UuidV7.generate(),
            tenantId = tenantId,
            oltId = oltId,
            label = validateLabel(label),
            description = description?.trim()?.takeIf { it.isNotEmpty() },
            status = status,
        )

        fun rehydrate(
            id: UUID,
            tenantId: UUID,
            oltId: UUID,
            label: String,
            description: String?,
            status: AssetStatus,
        ): PonPort = PonPort(id, tenantId, oltId, label, description, status)

        private fun validateLabel(label: String): String {
            val trimmed = label.trim()
            if (!LABEL_PATTERN.matches(trimmed)) {
                throw ValidationException("Label PON port '$label' tidak valid, gunakan notasi slot/port seperti '1/2/3'")
            }
            return trimmed
        }
    }
}

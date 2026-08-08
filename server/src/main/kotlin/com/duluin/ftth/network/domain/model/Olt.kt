package com.duluin.ftth.network.domain.model

import com.duluin.ftth.common.domain.UuidV7
import com.duluin.ftth.common.domain.error.ValidationException
import com.duluin.ftth.common.domain.geo.Coordinate
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
    snmpPort: Int,
    status: AssetStatus,
    location: Coordinate,
    areaId: UUID?,
    description: String?,
    snmpEnabled: Boolean,
    snmpVersion: SnmpVersion,
    webEnabled: Boolean,
    webProtocol: WebProtocol,
    webPort: Int?,
    webUsername: String?,
    webPassword: String?,
) {
    var siteId: UUID = siteId
        private set

    var name: String = name
        private set

    /**
     * Titik OLT di peta. Berdiri sendiri (bukan sekadar menempel di site) supaya
     * saat OLT mati, perangkatnya terlihat merah beserta seluruh jalur hilirnya.
     * Baku diwarisi dari lokasi site-nya, tapi operator bisa menaruhnya sendiri.
     */
    var location: Coordinate = location
        private set

    /** Area scope untuk penyaringan tile — diwarisi dari site (OLT tinggal di site-nya). */
    var areaId: UUID? = areaId
        private set

    var vendor: OltVendor = vendor
        private set

    var model: String? = model
        private set

    var managementIp: ManagementIp? = managementIp
        private set

    var snmpCommunity: String? = snmpCommunity
        private set

    /**
     * Port SNMP perangkat. Baku 161, tapi sebagian OLT (mis. yang diekspos lewat
     * NAT/DMZ) mendengarkan di port non-standar — collector memakai nilai ini alih-alih
     * berasumsi 161, kalau tidak perangkat semacam itu tak akan pernah terjawab.
     */
    var snmpPort: Int = snmpPort
        private set

    var status: AssetStatus = status
        private set

    /** Catatan bebas operator (mis. lokasi rak, kontak vendor, ID kontrak). */
    var description: String? = description
        private set

    /** Kanal SNMP aktif — dicabut untuk OLT yang dikelola murni lewat Web UI (mis. HSGQ HTTP). */
    var snmpEnabled: Boolean = snmpEnabled
        private set

    var snmpVersion: SnmpVersion = snmpVersion
        private set

    /** Kanal Web UI/HTTP aktif — dipakai untuk metrik (suhu, daya optik) / manajemen langsung. */
    var webEnabled: Boolean = webEnabled
        private set

    var webProtocol: WebProtocol = webProtocol
        private set

    var webPort: Int? = webPort
        private set

    var webUsername: String? = webUsername
        private set

    /** Dipegang plaintext di domain; adapter persistence yang mengenkripsinya, sama seperti [snmpCommunity]. */
    var webPassword: String? = webPassword
        private set

    fun update(
        siteId: UUID,
        name: String,
        vendor: OltVendor,
        model: String?,
        managementIp: ManagementIp?,
        snmpPort: Int,
        location: Coordinate,
        areaId: UUID?,
        description: String?,
        snmpEnabled: Boolean,
        snmpVersion: SnmpVersion,
        webEnabled: Boolean,
        webProtocol: WebProtocol,
        webPort: Int?,
        webUsername: String?,
    ) {
        this.siteId = siteId
        this.name = AssetNaming.name(name, "OLT")
        this.vendor = vendor
        this.model = model?.trim()?.takeIf { it.isNotEmpty() }
        this.managementIp = managementIp
        this.snmpPort = validPort(snmpPort)
        this.location = location
        this.areaId = areaId
        this.description = description?.trim()?.takeIf { it.isNotEmpty() }
        this.snmpEnabled = snmpEnabled
        this.snmpVersion = snmpVersion
        this.webEnabled = webEnabled
        this.webProtocol = webProtocol
        this.webPort = webPort?.let(::validPort)
        this.webUsername = webUsername?.trim()?.takeIf { it.isNotEmpty() }
    }

    /** Memindah titik OLT di peta tanpa menyentuh atribut lain. */
    fun relocate(location: Coordinate) {
        this.location = location
    }

    /** `null` berarti "jangan ubah"; string kosong berarti "hapus kredensial". */
    fun changeSnmpCommunity(community: String?) {
        if (community == null) return
        snmpCommunity = community.trim().takeIf { it.isNotEmpty() }
    }

    /** `null` berarti "jangan ubah"; string kosong berarti "hapus password Web". */
    fun changeWebPassword(password: String?) {
        if (password == null) return
        webPassword = password.trim().takeIf { it.isNotEmpty() }
    }

    fun changeStatus(status: AssetStatus) {
        this.status = status
    }

    /**
     * Collector hanya bisa mem-polling OLT yang kanal SNMP-nya aktif, vendornya
     * didukung, sekaligus punya alamat dan kredensial. Dipakai UI untuk menandai
     * OLT yang "terinventarisasi tapi belum termonitor".
     */
    fun isPollable(): Boolean =
        snmpEnabled && vendor.monitoringSupported() && managementIp != null && !snmpCommunity.isNullOrBlank()

    companion object {
        /** Port SNMP baku bila operator tidak menyetel sendiri. */
        const val DEFAULT_SNMP_PORT = 161

        fun create(
            tenantId: UUID,
            siteId: UUID,
            code: String,
            name: String,
            vendor: OltVendor,
            model: String?,
            managementIp: ManagementIp?,
            snmpCommunity: String?,
            location: Coordinate,
            areaId: UUID?,
            snmpPort: Int = DEFAULT_SNMP_PORT,
            status: AssetStatus = AssetStatus.ACTIVE,
            description: String? = null,
            snmpEnabled: Boolean = true,
            snmpVersion: SnmpVersion = SnmpVersion.V2C,
            webEnabled: Boolean = false,
            webProtocol: WebProtocol = WebProtocol.HTTP,
            webPort: Int? = null,
            webUsername: String? = null,
            webPassword: String? = null,
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
            snmpPort = validPort(snmpPort),
            status = status,
            location = location,
            areaId = areaId,
            description = description?.trim()?.takeIf { it.isNotEmpty() },
            snmpEnabled = snmpEnabled,
            snmpVersion = snmpVersion,
            webEnabled = webEnabled,
            webProtocol = webProtocol,
            webPort = webPort?.let(::validPort),
            webUsername = webUsername?.trim()?.takeIf { it.isNotEmpty() },
            webPassword = webPassword?.trim()?.takeIf { it.isNotEmpty() },
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
            snmpPort: Int,
            status: AssetStatus,
            location: Coordinate,
            areaId: UUID?,
            description: String?,
            snmpEnabled: Boolean,
            snmpVersion: SnmpVersion,
            webEnabled: Boolean,
            webProtocol: WebProtocol,
            webPort: Int?,
            webUsername: String?,
            webPassword: String?,
        ): Olt = Olt(
            id, tenantId, code, siteId, name, vendor, model, managementIp,
            snmpCommunity, snmpPort, status, location, areaId,
            description, snmpEnabled, snmpVersion, webEnabled, webProtocol, webPort, webUsername, webPassword,
        )

        private fun validPort(port: Int): Int {
            if (port !in 1..65535) {
                throw ValidationException("Port $port di luar rentang 1..65535")
            }
            return port
        }
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

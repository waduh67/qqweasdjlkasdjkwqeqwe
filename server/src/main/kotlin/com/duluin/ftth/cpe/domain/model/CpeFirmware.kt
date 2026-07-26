package com.duluin.ftth.cpe.domain.model

/**
 * Satu berkas firmware yang tersedia di ACS untuk di-push ke perangkat (TR-069
 * Download, `fileType` "1 Firmware Upgrade Image"). [name] adalah identitas berkas
 * di GenieACS (dipakai saat memicu unduh); [productClass]/[oui] membatasi berkas ke
 * model perangkat yang cocok — null berarti berlaku umum.
 */
data class FirmwareFile(
    val name: String,
    val version: String?,
    val productClass: String?,
    val oui: String?,
    /** Jenis berkas TR-069 (mis. "1 Firmware Upgrade Image"); detail integrasi ACS. */
    val fileType: String,
    val sizeBytes: Long?,
) {
    /** Cocok untuk perangkat ber-[deviceProductClass]/[deviceOui] ini? null = berlaku umum. */
    fun appliesTo(deviceProductClass: String?, deviceOui: String?): Boolean {
        val classOk = productClass == null || deviceProductClass == null ||
            productClass.equals(deviceProductClass, ignoreCase = true)
        val ouiOk = oui == null || deviceOui == null || oui.equals(deviceOui, ignoreCase = true)
        return classOk && ouiOk
    }

    companion object {
        const val FIRMWARE_FILE_TYPE = "1 Firmware Upgrade Image"
    }
}

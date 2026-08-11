package com.duluin.ftth.network.domain.model

import com.duluin.ftth.common.domain.UuidV7
import com.duluin.ftth.common.domain.error.ValidationException
import com.duluin.ftth.common.domain.geo.Coordinate
import java.util.UUID

/**
 * Sisi sebuah port ODF. Satu port adalah satu adapter yang punya dua mulut, dan
 * keduanya benar-benar disambung orang:
 *
 *  - [BACK] menghadap kabel luar — core feeder di-splice ke pigtail di sini,
 *    lalu tak disentuh lagi selama bertahun-tahun.
 *  - [FRONT] menghadap OLT — patchcord dicolok di sini, dan inilah yang dicabut
 *    teknisi tiap kali sebuah pelanggan pindah PON port.
 *
 * Memisahkannya bukan kerewelan model: tanpa sisi, "core mana ke PON mana" tak
 * bisa dicatat utuh, dan tiap penelusuran jalur berhenti di depan rak.
 */
enum class OdfPortSide(val label: String) {
    BACK("Belakang"),
    FRONT("Depan"),
}

/**
 * ODF — rak terminasi serat di dalam POP.
 *
 * Ia bukan perangkat aktif: tak ada listrik, tak ada splitter, tak ada yang bisa
 * mati di dalamnya. Yang ada cuma deretan adapter tempat kabel luar berhenti dan
 * patchcord dimulai — titik demarkasi antara jaringan luar dan isi rak.
 *
 * Kehadirannya OPSIONAL dan itu disengaja: ISP kecil yang OLT-nya menempel di
 * dinding memang menyambung feeder langsung ke pigtail OLT, dan model ini tak
 * boleh memaksa mereka mengarang rak yang tak ada.
 */
class Odf private constructor(
    val id: UUID,
    val tenantId: UUID,
    val code: String,
    name: String,
    siteId: UUID,
    location: Coordinate,
    areaId: UUID?,
    portCount: Int,
    status: AssetStatus,
) {
    var name: String = name
        private set

    /** POP tempat rak ini berdiri. Wajib — tak ada ODF di pinggir jalan. */
    var siteId: UUID = siteId
        private set

    var location: Coordinate = location
        private set

    var areaId: UUID? = areaId
        private set

    /** Jumlah adapter di rak; batas atas nomor port yang boleh disambung. */
    var portCount: Int = portCount
        private set

    var status: AssetStatus = status
        private set

    @Suppress("LongParameterList")
    fun update(
        name: String,
        siteId: UUID,
        location: Coordinate,
        areaId: UUID?,
        portCount: Int,
        status: AssetStatus,
    ) {
        this.name = AssetNaming.name(name, "ODF")
        this.siteId = siteId
        this.location = location
        this.areaId = areaId
        this.portCount = validatePortCount(portCount)
        this.status = status
    }

    /** Memindah titik ODF di peta tanpa menyentuh atribut lain. */
    fun relocate(location: Coordinate) {
        this.location = location
    }

    /**
     * Apakah nomor port ini benar-benar ada di rak. Dipakai sebelum menyambung:
     * port 97 pada rak 96-port adalah salah ketik, bukan port yang belum dipasang.
     */
    fun hasPort(portNumber: Int): Boolean = portNumber in 1..portCount

    companion object {
        const val MAX_PORT_COUNT = 1152

        @Suppress("LongParameterList")
        fun create(
            tenantId: UUID,
            code: String,
            name: String,
            siteId: UUID,
            location: Coordinate,
            areaId: UUID?,
            portCount: Int,
            status: AssetStatus = AssetStatus.ACTIVE,
        ): Odf = Odf(
            id = UuidV7.generate(),
            tenantId = tenantId,
            code = AssetNaming.code(code, "ODF"),
            name = AssetNaming.name(name, "ODF"),
            siteId = siteId,
            location = location,
            areaId = areaId,
            portCount = validatePortCount(portCount),
            status = status,
        )

        @Suppress("LongParameterList")
        fun rehydrate(
            id: UUID,
            tenantId: UUID,
            code: String,
            name: String,
            siteId: UUID,
            location: Coordinate,
            areaId: UUID?,
            portCount: Int,
            status: AssetStatus,
        ): Odf = Odf(id, tenantId, code, name, siteId, location, areaId, portCount, status)

        private fun validatePortCount(portCount: Int): Int {
            if (portCount !in 1..MAX_PORT_COUNT) {
                throw ValidationException("Jumlah port ODF harus 1-$MAX_PORT_COUNT")
            }
            return portCount
        }
    }
}

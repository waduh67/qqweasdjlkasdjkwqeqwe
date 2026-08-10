package com.duluin.ftth.network.domain.model

import com.duluin.ftth.common.domain.UuidV7
import com.duluin.ftth.common.domain.error.ValidationException
import com.duluin.ftth.common.domain.geo.Coordinate
import java.util.UUID

/**
 * Joint box — kotak sambung serat di tengah atau di ujung jalur kabel.
 *
 * Bedanya dengan ODC/ODP cuma satu, tapi menentukan: DI DALAMNYA TIDAK ADA
 * SPLITTER. Ia tidak membagi cahaya, hanya menyambung serat ke serat. Karena itu
 * ia tak punya "kaki keluaran" yang bisa direbutkan kabel — yang dicatat di
 * dalamnya adalah sambungan core, bukan port.
 *
 * Kehadirannya bukan detail kosmetik: tiap sambungan menambah redaman dan jadi
 * tersangka pertama saat jalur bermasalah. JB yang tak tercatat membuat anggaran
 * redaman selalu meleset dan teknisi mencari titik yang tak ada di peta.
 */
class JointBox private constructor(
    val id: UUID,
    val tenantId: UUID,
    val code: String,
    name: String,
    address: String?,
    location: Coordinate,
    areaId: UUID?,
    trayCount: Int,
    capacity: Int,
    status: AssetStatus,
) {
    var name: String = name
        private set

    var address: String? = address
        private set

    var location: Coordinate = location
        private set

    var areaId: UUID? = areaId
        private set

    /** Jumlah tray di dalam kotak — informasi lapangan, bukan batas yang ditegakkan. */
    var trayCount: Int = trayCount
        private set

    /** Batas jumlah sambungan yang muat; inilah yang ditegakkan saat menyambung. */
    var capacity: Int = capacity
        private set

    var status: AssetStatus = status
        private set

    @Suppress("LongParameterList")
    fun update(
        name: String,
        address: String?,
        location: Coordinate,
        areaId: UUID?,
        trayCount: Int,
        capacity: Int,
        status: AssetStatus,
    ) {
        this.name = AssetNaming.name(name, "joint box")
        this.address = AssetNaming.address(address)
        this.location = location
        this.areaId = areaId
        this.trayCount = validateTrayCount(trayCount)
        this.capacity = validateCapacity(capacity)
        this.status = status
    }

    /** Memindah titik JB di peta tanpa menyentuh atribut lain. */
    fun relocate(location: Coordinate) {
        this.location = location
    }

    companion object {
        const val MAX_TRAY_COUNT = 64
        const val MAX_CAPACITY = 1536

        @Suppress("LongParameterList")
        fun create(
            tenantId: UUID,
            code: String,
            name: String,
            address: String?,
            location: Coordinate,
            areaId: UUID?,
            trayCount: Int,
            capacity: Int,
            status: AssetStatus = AssetStatus.ACTIVE,
        ): JointBox = JointBox(
            id = UuidV7.generate(),
            tenantId = tenantId,
            code = AssetNaming.code(code, "joint box"),
            name = AssetNaming.name(name, "joint box"),
            address = AssetNaming.address(address),
            location = location,
            areaId = areaId,
            trayCount = validateTrayCount(trayCount),
            capacity = validateCapacity(capacity),
            status = status,
        )

        @Suppress("LongParameterList")
        fun rehydrate(
            id: UUID,
            tenantId: UUID,
            code: String,
            name: String,
            address: String?,
            location: Coordinate,
            areaId: UUID?,
            trayCount: Int,
            capacity: Int,
            status: AssetStatus,
        ): JointBox = JointBox(id, tenantId, code, name, address, location, areaId, trayCount, capacity, status)

        private fun validateTrayCount(trayCount: Int): Int {
            if (trayCount !in 1..MAX_TRAY_COUNT) {
                throw ValidationException("Jumlah tray joint box harus 1-$MAX_TRAY_COUNT")
            }
            return trayCount
        }

        private fun validateCapacity(capacity: Int): Int {
            if (capacity !in 1..MAX_CAPACITY) {
                throw ValidationException("Kapasitas sambungan joint box harus 1-$MAX_CAPACITY")
            }
            return capacity
        }
    }
}

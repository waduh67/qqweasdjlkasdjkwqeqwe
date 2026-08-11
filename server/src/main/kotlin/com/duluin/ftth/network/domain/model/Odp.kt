package com.duluin.ftth.network.domain.model

import com.duluin.ftth.common.domain.UuidV7
import com.duluin.ftth.common.domain.error.ConflictException
import com.duluin.ftth.common.domain.error.ValidationException
import com.duluin.ftth.common.domain.geo.Coordinate
import java.time.LocalDate
import java.util.UUID

/**
 * Optical Distribution Point — kotak terminasi di tiang/dinding, tempat kabel
 * drop ke rumah pelanggan ditarik.
 *
 * Simpul paling sibuk di operasional harian: pertanyaan "port mana yang kosong di
 * ODP ini?" dan "siapa saja yang mati kalau ODP ini bermasalah?" bermuara di sini.
 *
 * Splitter di dalamnya benda tersendiri (lihat [Splitter]) — biasanya satu modul,
 * tapi kotak tanpa splitter (murni sambungan lewat) juga ada.
 */
class Odp private constructor(
    val id: UUID,
    val tenantId: UUID,
    val code: String,
    name: String,
    address: String?,
    location: Coordinate,
    areaId: UUID?,
    odcId: UUID?,
    capacity: Int,
    status: AssetStatus,
    installedOn: LocalDate?,
    mounting: MountingType?,
    notes: String?,
) {
    var name: String = name
        private set

    var address: String? = address
        private set

    var location: Coordinate = location
        private set

    var areaId: UUID? = areaId
        private set

    var odcId: UUID? = odcId
        private set

    var capacity: Int = capacity
        private set

    var status: AssetStatus = status
        private set

    /** Tanggal pemasangan — umur aset, dasar jadwal preventif & klaim garansi. */
    var installedOn: LocalDate? = installedOn
        private set

    /** Dudukan kotak; menentukan alat yang dibawa teknisi. Lihat [MountingType]. */
    var mounting: MountingType? = mounting
        private set

    /** Pesan teknisi untuk teknisi berikutnya — "kunci di pos satpam", dst. */
    var notes: String? = notes
        private set

    @Suppress("LongParameterList")
    fun update(
        name: String,
        address: String?,
        location: Coordinate,
        areaId: UUID?,
        capacity: Int,
        status: AssetStatus,
        installedOn: LocalDate?,
        mounting: MountingType?,
        notes: String?,
    ) {
        this.name = AssetNaming.name(name, "ODP")
        this.address = AssetNaming.address(address)
        this.location = location
        this.areaId = areaId
        this.capacity = validateCapacity(capacity)
        this.status = status
        this.installedOn = installedOn
        this.mounting = mounting
        this.notes = AssetNaming.notes(notes)
    }

    fun connectTo(odcId: UUID?) {
        this.odcId = odcId
    }

    /** Memindah titik ODP di peta tanpa menyentuh atribut lain. */
    fun relocate(location: Coordinate) {
        this.location = location
    }

    /**
     * Memastikan sebuah port boleh dipakai ONU baru. Melempar, bukan mengembalikan
     * boolean, supaya pemanggil tidak bisa "lupa" memeriksa hasilnya dan agar
     * alasan penolakan sampai ke pengguna apa adanya.
     *
     * @param occupiedPorts nomor port yang sudah terpakai, disuplai pemanggil
     *        karena okupansi hidup di agregat ONU (module customer).
     */
    fun assertPortAssignable(portNumber: Int, occupiedPorts: Set<Int>) {
        if (!status.acceptsService()) {
            throw ConflictException("ODP $code berstatus $status sehingga belum bisa dipasangi pelanggan")
        }
        if (portNumber !in 1..capacity) {
            throw ValidationException("Port $portNumber di luar kapasitas ODP $code (1-$capacity)")
        }
        if (portNumber in occupiedPorts) {
            throw ConflictException("Port $portNumber pada ODP $code sudah terpakai")
        }
    }

    /** Sisa port yang masih bisa dijual — dasar heatmap utilisasi & capacity planning. */
    fun availablePorts(occupiedPorts: Set<Int>): List<Int> = (1..capacity).filterNot { it in occupiedPorts }

    companion object {
        const val MAX_CAPACITY = 256

        @Suppress("LongParameterList")
        fun create(
            tenantId: UUID,
            code: String,
            name: String,
            address: String?,
            location: Coordinate,
            areaId: UUID?,
            odcId: UUID?,
            capacity: Int,
            status: AssetStatus = AssetStatus.ACTIVE,
            installedOn: LocalDate? = null,
            mounting: MountingType? = null,
            notes: String? = null,
        ): Odp = Odp(
            id = UuidV7.generate(),
            tenantId = tenantId,
            code = AssetNaming.code(code, "ODP"),
            name = AssetNaming.name(name, "ODP"),
            address = AssetNaming.address(address),
            location = location,
            areaId = areaId,
            odcId = odcId,
            capacity = validateCapacity(capacity),
            status = status,
            installedOn = installedOn,
            mounting = mounting,
            notes = AssetNaming.notes(notes),
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
            odcId: UUID?,
            capacity: Int,
            status: AssetStatus,
            installedOn: LocalDate?,
            mounting: MountingType?,
            notes: String?,
        ): Odp = Odp(
            id, tenantId, code, name, address, location, areaId, odcId, capacity, status,
            installedOn, mounting, notes,
        )

        private fun validateCapacity(capacity: Int): Int {
            if (capacity !in 1..MAX_CAPACITY) {
                throw ValidationException("Kapasitas ODP harus 1-$MAX_CAPACITY")
            }
            return capacity
        }
    }
}

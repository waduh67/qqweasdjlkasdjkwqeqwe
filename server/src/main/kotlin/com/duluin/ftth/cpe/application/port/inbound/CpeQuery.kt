package com.duluin.ftth.cpe.application.port.inbound

import java.util.UUID

/** Sisi baca module cpe: proyeksi tersimpan + keadaan langsung dari ACS. */
interface CpeQuery {

    /** CPE milik satu pelanggan (0..n; biasanya satu). */
    fun devicesForCustomer(customerId: UUID): List<CpeDeviceView>

    /** Detail satu device beserta riwayat aksi terakhirnya. */
    fun get(deviceId: UUID): CpeDeviceDetail

    /**
     * Keadaan langsung — WiFi & host tersambung — dibaca dari ACS saat ini. Lebih
     * lambat dari [get] karena memanggil NBI, jadi dipisah: UI memuatnya hanya saat
     * panel keadaan dibuka.
     */
    fun liveState(deviceId: UUID): CpeLiveView

    /**
     * Berkas firmware di ACS yang cocok untuk model perangkat ini — pilihan sasaran
     * upgrade. Dibaca on-demand dari NBI, tidak tersimpan.
     */
    fun availableFirmware(deviceId: UUID): List<FirmwareFileView>
}

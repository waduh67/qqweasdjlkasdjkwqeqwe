package com.duluin.ftth.network.domain.model

/**
 * Cara sebuah kotak menempel di dunia nyata.
 *
 * Bukan kosmetik: inilah yang menentukan apa yang harus dibawa teknisi sebelum
 * berangkat. Kotak di tiang berarti tangga atau bucket truck dan izin naik;
 * kotak bawah tanah berarti kunci handhole, pompa air, dan kadang izin galian;
 * kabinet pedestal bisa dibuka sambil berdiri tapi rawan tersenggol kendaraan.
 * Tanpa catatan ini, penugasan perbaikan berangkat dengan alat yang salah dan
 * pulang tanpa hasil.
 *
 * Nullable di semua aset: data lama tak pernah ditanyai hal ini, dan menebak
 * dudukan kotak yang tak pernah dilihat orang lebih buruk daripada mengaku
 * belum tahu.
 */
enum class MountingType(val label: String) {
    /** Menempel di tiang — tiang listrik/telkom milik sendiri maupun sewa. */
    POLE("Tiang"),

    /** Dibaut ke dinding bangunan; lazim untuk ODP di ruko dan rumah susun. */
    WALL("Dinding"),

    /** Digantung pada kabel seling di antara dua tiang, tanpa menyentuh tiang. */
    AERIAL("Gantung di kabel"),

    /** Berdiri di atas pondasi di tanah — bentuk paling lazim untuk ODC. */
    PEDESTAL("Pedestal (berdiri di tanah)"),

    /** Di dalam handhole/manhole; jalur kabelnya tanam. */
    UNDERGROUND("Bawah tanah (handhole)"),

    /** Di dalam ruangan — POP, shelter, atau ruang panel gedung. */
    INDOOR("Dalam ruangan"),
}

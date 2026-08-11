package com.duluin.ftth.network.application.port.inbound

import com.duluin.ftth.network.domain.model.ClosureKind
import com.duluin.ftth.network.domain.model.ConnectionPointKind
import com.duluin.ftth.network.domain.model.OdfPortSide
import com.duluin.ftth.network.domain.model.SpliceMethod
import java.util.UUID

/**
 * Sambung & putus serat di dalam sebuah closure.
 *
 * Sengaja hanya tiga kata kerja — lihat isi closure, sambung, putus — karena
 * itulah yang benar-benar dilakukan di lapangan. Tak ada "pindahkan": memindah
 * sambungan berarti membuka pelindung, memotong, lalu menyambung lagi, dan
 * mencatatnya sebagai satu operasi diam-diam menghapus jejak bahwa jalur lama
 * pernah ada.
 *
 * Bidang [UpdateFiberConnectionCommand] terpisah karena hasil ukur redaman
 * sering baru masuk belakangan (OPM baru dibawa besoknya) — itu memperbarui
 * catatan, bukan mengubah apa tersambung ke apa.
 */
interface ManageFiberConnectionUseCase {

    fun list(closureKind: ClosureKind, closureId: UUID): ClosureSpliceView

    fun connect(command: ConnectFiberCommand): FiberConnectionView

    fun update(id: UUID, command: UpdateFiberConnectionCommand): FiberConnectionView

    fun disconnect(id: UUID)

    /**
     * Memutus semua sambungan yang menyentuh core kabel ini. Dipanggil sebelum
     * kabel dihapus: core ikut lenyap bersama kabelnya, dan sambungan yang
     * menggantung ke serat yang tak ada lagi adalah kebohongan yang paling
     * mahal — ia membuat telusur jalur menunjuk ke jalur yang sudah digulung.
     */
    fun disconnectAllOfCable(cableId: UUID)
}

/** Satu ujung yang mau disambung; bentuknya diperiksa domain (lihat ConnectionPoint). */
data class ConnectionPointCommand(
    val kind: ConnectionPointKind,
    val coreId: UUID? = null,
    val nodeId: UUID? = null,
    val portNumber: Int? = null,
    /** Sisi port ODF (belakang/depan); wajib untuk ODF_PORT, terlarang untuk lainnya. */
    val portSide: OdfPortSide? = null,
)

data class ConnectFiberCommand(
    val closureKind: ClosureKind,
    val closureId: UUID,
    val a: ConnectionPointCommand,
    val b: ConnectionPointCommand,
    val method: SpliceMethod = SpliceMethod.FUSION,
    val lossDb: Double? = null,
    val note: String? = null,
)

data class UpdateFiberConnectionCommand(
    val method: SpliceMethod,
    val lossDb: Double? = null,
    val note: String? = null,
)

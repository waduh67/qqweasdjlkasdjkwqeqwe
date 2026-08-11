package com.duluin.ftth.workorder.application.service

import com.duluin.ftth.network.SpliceWorkOrderPort
import com.duluin.ftth.network.SpliceWorkOrderRef
import com.duluin.ftth.workorder.application.port.outbound.WorkOrderRepository
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

/**
 * Mengisi kontrak yang dideklarasikan module network: menyahut kalau ditanya
 * "tiket ini ada?", dan menuliskan pekerjaan serat ke linimasa tiketnya.
 *
 * Berdiri di sisi workorder, bukan network, supaya arah dependensinya tetap
 * satu arah — alasan lengkapnya di [SpliceWorkOrderPort].
 */
@Component
@Transactional(readOnly = true)
class SpliceWorkOrderAdapter(
    private val workOrders: WorkOrderRepository,
) : SpliceWorkOrderPort {

    override fun findWorkOrder(id: UUID): SpliceWorkOrderRef? = workOrders.findById(id)?.let {
        SpliceWorkOrderRef(id = it.id, code = it.code, title = it.title, open = it.status.open)
    }

    /**
     * Tiket yang tak ditemukan dilewati tanpa suara — lihat kontraknya. Yang
     * memanggil sudah terlanjur menyimpan pekerjaan nyata; seratnya di dalam
     * kotak tak ikut lepas hanya karena tiketnya keburu dihapus.
     */
    @Transactional
    override fun noteSpliceActivity(workOrderId: UUID, message: String, actorId: UUID?) {
        val workOrder = workOrders.findById(workOrderId) ?: return
        workOrder.noteFieldActivity(message, Instant.now(), actorId)
        workOrders.save(workOrder)
    }
}

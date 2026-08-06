package com.duluin.ftth.billing.application.service

import com.duluin.ftth.billing.application.port.outbound.PivotMasterConfigRepository
import com.duluin.ftth.billing.domain.model.PivotMasterConfig
import com.duluin.ftth.billing.domain.model.PivotMasterContext
import org.springframework.modulith.NamedInterface
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/**
 * Titik baca tunggal setelan MASTER Pivot: mengubah baris config platform → [PivotMasterContext]
 * terdekripsi siap-pakai (null bila platform belum mengonfigurasi/menyalakan Pivot).
 *
 * Bagian named interface `gateway` — di-expose agar `platformbilling` (penagihan langganan SaaS)
 * memakai akun master yang sama seperti tagihan pelanggan, tanpa menembus batas enkapsulasi
 * billing lain. Pola sama seperti [PaymentGatewayRegistry].
 */
@NamedInterface("gateway")
@Component
class PivotMasterConfigProvider(
    private val repository: PivotMasterConfigRepository,
) {
    /** Setelan master terdekripsi, atau null bila Pivot belum aktif/lengkap di level platform. */
    @Transactional(readOnly = true)
    fun current(): PivotMasterContext? = repository.find()?.resolveContext()

    /** Baris config apa adanya (bawaan bila belum pernah ada) — untuk sisi setelan admin. */
    @Transactional(readOnly = true)
    fun config(): PivotMasterConfig = repository.find() ?: PivotMasterConfig.default()
}

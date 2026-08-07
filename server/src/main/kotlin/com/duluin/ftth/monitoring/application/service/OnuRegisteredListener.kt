package com.duluin.ftth.monitoring.application.service

import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.customer.OnuRegistered
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener

/**
 * Menuntaskan kotak masuk provisioning saat sebuah ONU didaftarkan di LUAR kotak masuk
 * (mis. operator mencoloknya manual dari halaman pelanggan).
 *
 * Tanpa ini, baris "Menunggu" berserial sama baru bersih ketika siklus polling berikutnya
 * menyadari serialnya kini dikenal (rekonsiliasi malas di
 * [DiscoveredOnuRecorder.resolveKnown]) — sehingga sekilas tampak "kok masih menggantung".
 * Listener ini melakukannya SINKRON: begitu registrasi ter-commit, baris DISCOVERED
 * berserial sama langsung ditandai PROVISIONED.
 *
 * Pola sama dengan [OltDeletedListener]: fase AFTER_COMMIT (hanya bereaksi pada registrasi
 * yang benar-benar ter-commit), tenant context dipasang dari event — bukan thread saat ini —
 * agar method transaksional di dalamnya memasang GUC `app.tenant_id` yang benar (RLS), dan
 * `@Transactional` sengaja TIDAK ada di listener sebab transaksinya akan terlanjur dibuka
 * sebelum tenant di-set; batas transaksinya ada di [DiscoveredOnuService.resolveRegistered]
 * (REQUIRES_NEW). Kegagalan rekonsiliasi tak boleh menggagalkan registrasi ONU yang sudah
 * ter-commit — paling buruk baris beres sendiri saat poll berikutnya.
 */
@Component
class OnuRegisteredListener(
    private val discoveredOnu: DiscoveredOnuService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    fun on(event: OnuRegistered) {
        try {
            TenantContext.runAs(event.tenantId) {
                val resolved = discoveredOnu.resolveRegistered(event.serialNumber)
                if (resolved > 0) {
                    log.info(
                        "Menuntaskan {} baris ONU terdeteksi untuk serial {} yang didaftarkan di luar kotak masuk",
                        resolved, event.serialNumber,
                    )
                }
            }
        } catch (ex: Exception) {
            log.warn("Gagal menuntaskan kotak masuk untuk ONU terdaftar serial {}", event.serialNumber, ex)
        }
    }
}

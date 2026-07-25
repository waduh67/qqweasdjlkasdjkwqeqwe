package com.duluin.ftth.monitoring.application.service

import com.duluin.ftth.contract.OnuReading
import com.duluin.ftth.monitoring.application.port.outbound.DiscoveredOnuRepository
import com.duluin.ftth.monitoring.domain.model.DiscoveredOnu
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Menangkap ONU liar dari aliran ingestion ke kotak masuk provisioning.
 *
 * Dipanggil dari dalam transaksi [MetricIngestionService], jadi metodenya ikut
 * transaksi yang sama (REQUIRED, bukan REQUIRES_NEW): penangkapan ONU terdeteksi
 * dan pencatatan batch berhasil atau gagal bersama-sama, konsisten dengan sifat
 * "seluruhnya satu transaksi" milik ingestion.
 */
@Service
@Transactional
class DiscoveredOnuRecorder(
    private val repository: DiscoveredOnuRepository,
) {
    /**
     * Merawat baris kotak masuk untuk tiap serial yang tak dikenal dalam batch.
     * Satu baris per serial: yang sudah ada diperbarui dengan pengamatan TERBARU,
     * yang belum ada dibuat. Beberapa bacaan serial yang sama dalam satu batch
     * diringkas ke yang paling akhir teramati.
     */
    fun capture(tenantId: UUID, unknownReadings: List<OnuReading>, oltIdsByCode: Map<String, UUID>) {
        if (unknownReadings.isEmpty()) return
        unknownReadings
            .groupBy { it.serialNumber.trim().uppercase() }
            .forEach { (serial, readings) ->
                val reading = readings.maxByOrNull { it.observedAt } ?: return@forEach
                val oltId = oltIdsByCode[reading.oltCode.trim().uppercase()]
                val existing = repository.findBySerialNumber(serial)
                if (existing == null) {
                    repository.save(
                        DiscoveredOnu.discover(
                            tenantId = tenantId,
                            serialNumber = serial,
                            oltId = oltId,
                            oltCode = reading.oltCode,
                            ponPortLabel = reading.ponPortLabel,
                            lastStatus = reading.status.name,
                            lastRxPowerDbm = reading.rxPowerDbm,
                            at = reading.observedAt,
                        ),
                    )
                } else {
                    existing.observe(
                        status = reading.status.name,
                        rxPowerDbm = reading.rxPowerDbm,
                        oltId = oltId,
                        oltCode = reading.oltCode,
                        ponPortLabel = reading.ponPortLabel,
                        at = reading.observedAt,
                    )
                    repository.save(existing)
                }
            }
    }

    /**
     * Menuntaskan sendiri kotak masuk: serial yang kini dikenal namun masih punya
     * baris DISCOVERED (mis. didaftarkan langsung dari halaman pelanggan, di luar
     * kotak masuk) ditandai PROVISIONED agar tidak menggantung sebagai "menunggu".
     */
    fun resolveKnown(knownSerials: Set<String>) {
        repository.findDiscoveredBySerials(knownSerials).forEach {
            it.markProvisioned()
            repository.save(it)
        }
    }
}

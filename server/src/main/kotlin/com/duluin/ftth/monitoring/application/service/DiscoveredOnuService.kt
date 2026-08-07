package com.duluin.ftth.monitoring.application.service

import com.duluin.ftth.common.domain.error.ConflictException
import com.duluin.ftth.common.domain.error.NotFoundException
import com.duluin.ftth.common.domain.error.ValidationException
import com.duluin.ftth.customer.CustomerApi
import com.duluin.ftth.customer.ProvisionOnuCommand
import com.duluin.ftth.monitoring.application.port.inbound.DiscoveredOnuView
import com.duluin.ftth.monitoring.application.port.inbound.ManageDiscoveredOnuUseCase
import com.duluin.ftth.monitoring.application.port.inbound.ProvisionDiscoveredOnuCommand
import com.duluin.ftth.monitoring.application.port.inbound.ProvisioningSuggestion
import com.duluin.ftth.monitoring.application.port.outbound.DiscoveredOnuRepository
import com.duluin.ftth.monitoring.domain.model.DiscoveredOnu
import com.duluin.ftth.monitoring.domain.model.DiscoveredOnuState
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Sisi operator dari kotak masuk provisioning. Penautan ONU nyata (daftar +
 * pasang ke port ODP) didelegasikan ke module customer lewat `CustomerApi`;
 * service ini hanya memutuskan baris mana yang selesai dan menandainya.
 */
@Service
@Transactional(readOnly = true)
class DiscoveredOnuService(
    private val repository: DiscoveredOnuRepository,
    private val customerApi: CustomerApi,
    private val resolver: OnuProvisioningResolver,
    private val recorder: DiscoveredOnuRecorder,
) : ManageDiscoveredOnuUseCase {

    override fun list(state: DiscoveredOnuState?, oltId: UUID?): List<DiscoveredOnuView> {
        val effective = state ?: DiscoveredOnuState.DISCOVERED
        val rows = if (oltId != null) repository.findByStateAndOltId(effective, oltId) else repository.findByState(effective)
        // Saran auto-link hanya relevan untuk baris yang masih menuntut tindakan.
        val suggestions = if (effective.actionable) resolver.resolveAll(rows) else emptyMap()
        return rows.map { it.toView(suggestions[it.id]) }
    }

    @Transactional
    override fun provision(id: UUID, command: ProvisionDiscoveredOnuCommand): DiscoveredOnuView {
        val discovered = require(id)
        if (discovered.state == DiscoveredOnuState.PROVISIONED) {
            throw ConflictException("ONU ${discovered.serialNumber} sudah diprovisikan")
        }
        // ODP opsional, tapi harus utuh: ODP tanpa port (atau sebaliknya) ambigu.
        if ((command.odpId == null) != (command.portNumber == null)) {
            throw ValidationException("ODP dan nomor port harus diisi bersamaan, atau keduanya dikosongkan")
        }
        customerApi.provisionOnu(
            ProvisionOnuCommand(
                serialNumber = discovered.serialNumber,
                model = null,
                customerId = command.customerId,
                odpId = command.odpId,
                portNumber = command.portNumber,
                // Bila operator tak mengisi baseline, pakai redaman terakhir yang teramati.
                installRxPowerDbm = command.installRxPowerDbm ?: discovered.lastRxPowerDbm,
            ),
        )
        discovered.markProvisioned()
        return repository.save(discovered).toView()
    }

    @Transactional
    override fun ignore(id: UUID): DiscoveredOnuView {
        val discovered = require(id)
        discovered.ignore()
        return repository.save(discovered).toView()
    }

    @Transactional
    override fun delete(id: UUID) {
        val discovered = require(id)
        repository.deleteById(discovered.id)
    }

    /**
     * Membersihkan sisa kotak masuk milik OLT yang baru dihapus. Dipicu event
     * [com.duluin.ftth.network.OltDeletedEvent] lewat [OltDeletedListener] — reaksi
     * sistem, bukan aksi operator, jadi sengaja di luar [ManageDiscoveredOnuUseCase].
     * Pemanggil menjalankannya dalam tenant context agar RLS menyaring ke tenant OLT.
     *
     * REQUIRES_NEW karena dipanggil dari listener AFTER_COMMIT: transaksi penghapus
     * OLT sudah selesai, jadi penghapusan yatim harus berjalan di transaksinya sendiri
     * yang benar-benar di-commit — persis pola [IncidentReconciler.reconcile]. Tanpa
     * ini, query DELETE gagal dengan "No active transaction".
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun purgeForDeletedOlt(oltId: UUID): Int = repository.deleteByOltId(oltId)

    /**
     * Menuntaskan sendiri baris kotak masuk berserial [serialNumber] yang kini dikenal —
     * dipicu event [com.duluin.ftth.customer.OnuRegistered] lewat [OnuRegisteredListener]
     * saat ONU didaftarkan di LUAR kotak masuk (mis. dicolok manual dari halaman pelanggan).
     * Reaksi sistem, bukan aksi operator, jadi sengaja di luar [ManageDiscoveredOnuUseCase].
     * Pemanggil menjalankannya dalam tenant context agar RLS menyaring ke tenant yang benar.
     *
     * REQUIRES_NEW karena dipanggil dari listener AFTER_COMMIT (transaksi registrasi sudah
     * selesai) — sama alasannya dengan [purgeForDeletedOlt]. Serial dinormalkan agar cocok
     * penyimpanan DiscoveredOnu (uppercase). Mengembalikan jumlah baris yang dituntaskan.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun resolveRegistered(serialNumber: String): Int {
        val serial = serialNumber.trim().uppercase()
        if (serial.isBlank()) return 0
        return recorder.resolveKnown(setOf(serial))
    }

    private fun require(id: UUID): DiscoveredOnu =
        repository.findById(id) ?: throw NotFoundException("ONU terdeteksi $id tidak ditemukan")

    private fun DiscoveredOnu.toView(suggestion: ProvisioningSuggestion? = null) = DiscoveredOnuView(
        id = id,
        serialNumber = serialNumber,
        oltId = oltId,
        oltCode = oltCode,
        ponPortLabel = ponPortLabel,
        lastStatus = lastStatus,
        lastRxPowerDbm = lastRxPowerDbm,
        firstSeenAt = firstSeenAt,
        lastSeenAt = lastSeenAt,
        seenCount = seenCount,
        state = state,
        suggestion = suggestion,
    )
}

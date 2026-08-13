package com.duluin.ftth.cpe.application.service

import com.duluin.ftth.cpe.application.port.outbound.AcsGateway
import com.duluin.ftth.cpe.application.port.outbound.CpeActionLogRepository
import com.duluin.ftth.cpe.domain.model.CpeActionLog
import com.duluin.ftth.cpe.domain.model.CpeActionType
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Satu langkah sapuan "Segarkan Batch": connection request ke SATU perangkat plus baris
 * jejak auditnya, dalam transaksinya sendiri.
 *
 * Komponen terpisah dari [AcsConsoleService], bukan method privat — persis alasan
 * [CpeSyncScheduler]/[CpeSyncService] dipisah: `@Transactional` Spring bekerja lewat
 * proxy, jadi pemanggilan dari dalam kelas yang sama tak akan pernah dibungkus transaksi.
 *
 * REQUIRES_NEW mengurung kegagalan ke satu perangkat: NBI yang menolak perangkat ke-40
 * tak boleh me-rollback 39 baris audit sebelumnya — justru catatan itulah yang paling
 * dicari saat operator bertanya "tadi yang kena apa saja".
 */
@Component
class AcsBulkRefreshRunner(
    private val acsGateway: AcsGateway,
    private val actionLogRepository: CpeActionLogRepository,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun refreshOne(deviceId: UUID, genieacsId: String, actorId: UUID, actorEmail: String?): RefreshOutcome {
        val outcome = runCatching { acsGateway.requestConnection(genieacsId) }
        val connected = outcome.getOrNull() == true
        val message = when {
            outcome.isFailure -> "gagal menghubungi ACS: ${outcome.exceptionOrNull()?.message?.take(200)}"
            connected -> "ACS terhubung ke perangkat"
            else -> "perangkat tak menjawab; perintah diantre untuk inform berikutnya"
        }
        if (outcome.isFailure) log.warn("Segarkan batch device {} gagal: {}", deviceId, outcome.exceptionOrNull()?.message)

        // Sama seperti refresh satuan: "Not Connect" BUKAN kegagalan aksi — perangkat cuma
        // sedang tak menjawab. Hanya penolakan NBI yang dicatat FAILED.
        val detail = "Segarkan batch → $message".take(480)
        val entry = if (outcome.isSuccess) {
            CpeActionLog.succeeded(deviceId, CpeActionType.REFRESH_ACS, detail, actorId, actorEmail)
        } else {
            CpeActionLog.failed(deviceId, CpeActionType.REFRESH_ACS, detail, actorId, actorEmail)
        }
        actionLogRepository.save(entry)

        return when {
            outcome.isFailure -> RefreshOutcome.FAILED
            connected -> RefreshOutcome.CONNECTED
            else -> RefreshOutcome.QUEUED
        }
    }
}

/** Hasil satu connection request: terhubung seketika, diantre, atau NBI menolak. */
enum class RefreshOutcome { CONNECTED, QUEUED, FAILED }

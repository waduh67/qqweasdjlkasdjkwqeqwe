package com.duluin.ftth.bng.application.service

import com.duluin.ftth.bng.BngApi
import com.duluin.ftth.bng.SubscriberSessionRef
import com.duluin.ftth.bng.application.port.outbound.NasRepository
import com.duluin.ftth.bng.application.port.outbound.RadiusSessionRepository
import com.duluin.ftth.bng.application.port.outbound.RateProfileRepository
import com.duluin.ftth.bng.application.port.outbound.SubscriberAccessRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Implementasi [BngApi] untuk module lain. Membaca ulang proyeksi sesi yang sudah
 * dilaporkan collector — murni baca, tak menyentuh BRAS. Semua repositori tenant-aware
 * (RLS), jadi hasilnya ter-scope tenant aktif secara otomatis.
 */
@Service
@Transactional(readOnly = true)
class BngApiService(
    private val subscriberAccessRepository: SubscriberAccessRepository,
    private val radiusSessionRepository: RadiusSessionRepository,
    private val nasRepository: NasRepository,
    private val rateProfileRepository: RateProfileRepository,
) : BngApi {

    override fun findSubscriberSession(customerId: UUID): SubscriberSessionRef? {
        val accounts = subscriberAccessRepository.findByCustomerId(customerId)
        if (accounts.isEmpty()) return null

        // Satu pelanggan bisa punya beberapa akun (mis. unit kedua). Untuk telusur jalur,
        // yang dipilih adalah akun yang sesinya sedang online; kalau tak ada, yang pertama.
        val sessions = accounts.associateWith { radiusSessionRepository.findBySubscriberAccessId(it.id) }
        val chosen = accounts.firstOrNull { sessions[it]?.online == true } ?: accounts.first()
        val session = sessions[chosen]
        // Sumber NAS: dari sesi terkini bila ada (yang benar-benar dipakai login),
        // kalau belum pernah terpantau jatuh ke NAS yang ditugaskan pada akun.
        val nasId = session?.nasId ?: chosen.nasId

        return SubscriberSessionRef(
            subscriberAccessId = chosen.id,
            username = chosen.username,
            accessStatus = chosen.status.name,
            rateProfileName = rateProfileRepository.findById(chosen.rateProfileId)?.name,
            online = session?.online ?: false,
            framedIp = session?.framedIp,
            nasId = nasId,
            nasName = nasId?.let { nasRepository.findById(it)?.name },
            nasIp = session?.nasIp,
            uptimeSeconds = session?.uptimeSeconds,
            startedAt = session?.startedAt,
            lastSeenAt = session?.lastSeenAt,
        )
    }
}

package com.duluin.ftth.bng.application.service

import com.duluin.ftth.bng.BngApi
import com.duluin.ftth.bng.PppSecretRef
import com.duluin.ftth.bng.ProvisionAccessSpec
import com.duluin.ftth.bng.ProvisionedAccessRef
import com.duluin.ftth.bng.SubscriberSessionRef
import com.duluin.ftth.bng.application.port.inbound.ManageSubscriberAccessUseCase
import com.duluin.ftth.bng.application.port.inbound.ProvisionAccessCommand
import com.duluin.ftth.bng.application.port.outbound.NasAreaCoverageRepository
import com.duluin.ftth.bng.application.port.outbound.NasRepository
import com.duluin.ftth.bng.application.port.outbound.RadiusSessionRepository
import com.duluin.ftth.bng.application.port.outbound.RouterOsPort
import com.duluin.ftth.bng.application.port.outbound.SubscriberAccessRepository
import com.duluin.ftth.bng.domain.model.AuthType
import com.duluin.ftth.catalog.CatalogApi
import com.duluin.ftth.common.domain.error.ValidationException
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
    private val coverageRepository: NasAreaCoverageRepository,
    private val catalogApi: CatalogApi,
    private val manageAccess: ManageSubscriberAccessUseCase,
    private val routerOs: RouterOsPort,
) : BngApi {

    override fun resolveNasForArea(areaId: UUID): UUID? = coverageRepository.findNasIdByAreaId(areaId)

    override fun fetchPppSecretsFromNas(nasId: UUID): List<PppSecretRef> {
        val nas = nasRepository.findById(nasId)
            ?: throw ValidationException("BRAS $nasId tidak ditemukan")
        return routerOs.fetchPppSecrets(nas).map {
            PppSecretRef(
                name = it.name,
                password = it.password,
                profile = it.profile,
                service = it.service,
                comment = it.comment,
                disabled = it.disabled,
            )
        }
    }

    @Transactional
    override fun provisionAccess(command: ProvisionAccessSpec): ProvisionedAccessRef {
        val view = manageAccess.provision(
            ProvisionAccessCommand(
                subscriptionId = command.subscriptionId,
                username = command.username,
                secret = command.secret,
                planId = command.planId,
                nasId = command.nasId,
                authType = parseAuthType(command.authType),
                framedIp = command.framedIp,
            ),
        )
        return ProvisionedAccessRef(accessId = view.id, username = view.username, status = view.status)
    }

    /** Petakan string tipe layanan lintas-module ke [AuthType]; null/kosong → PPPOE. */
    private fun parseAuthType(raw: String?): AuthType =
        raw?.trim()?.takeIf { it.isNotEmpty() }?.let { value ->
            runCatching { AuthType.valueOf(value.uppercase()) }.getOrElse {
                throw ValidationException("Tipe layanan '$raw' tidak dikenal (PPPOE/HOTSPOT/DHCP/STATIC)")
            }
        } ?: AuthType.PPPOE

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
            rateProfileName = catalogApi.findPlanNetwork(chosen.planId)?.name,
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

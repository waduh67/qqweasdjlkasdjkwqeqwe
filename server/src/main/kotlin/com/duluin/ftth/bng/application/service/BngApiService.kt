package com.duluin.ftth.bng.application.service

import com.duluin.ftth.bng.AccessExportRef
import com.duluin.ftth.bng.BngApi
import com.duluin.ftth.bng.ImportedAccessRef
import com.duluin.ftth.bng.PppSecretRef
import com.duluin.ftth.bng.ProvisionAccessSpec
import com.duluin.ftth.bng.ProvisionedAccessRef
import com.duluin.ftth.bng.SubscriberPppoeLiveness
import com.duluin.ftth.bng.SubscriberSessionRef
import com.duluin.ftth.bng.application.port.inbound.ManageSubscriberAccessUseCase
import com.duluin.ftth.bng.application.port.inbound.ProvisionAccessCommand
import com.duluin.ftth.bng.application.port.inbound.ResetSecretCommand
import com.duluin.ftth.bng.application.port.inbound.UpdateAccessCommand
import com.duluin.ftth.bng.application.port.outbound.NasAreaCoverageRepository
import com.duluin.ftth.bng.application.port.outbound.NasRepository
import com.duluin.ftth.bng.application.port.outbound.RadiusSessionRepository
import com.duluin.ftth.bng.application.port.outbound.RouterOsPort
import com.duluin.ftth.bng.application.port.outbound.SubscriberAccessRepository
import com.duluin.ftth.bng.domain.model.AuthType
import com.duluin.ftth.catalog.CatalogApi
import com.duluin.ftth.common.domain.error.ValidationException
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant
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
    /**
     * Ambang basi sesi: baris `radacct` ber-`online=true` yang tak diperbarui melebihi
     * durasi ini dianggap sudah putus. Dipilih 3 menit — beberapa kali interval poll BRAS
     * (umumnya 30–60 dtk), jadi satu poll yang terlewat tak langsung memerahkan pelanggan,
     * tapi sesi yang benar-benar berakhir tetap ketahuan cepat.
     */
    @Value("\${ftth.bng.session-stale-after:PT3M}") private val sessionStaleAfter: Duration,
) : BngApi {

    override fun resolveNasForArea(areaId: UUID): UUID? = coverageRepository.findNasIdByAreaId(areaId)

    override fun resolveNasByName(name: String): UUID? =
        name.trim().takeIf { it.isNotEmpty() }?.let { nasRepository.findByNameIgnoreCase(it)?.id }

    override fun findAccessByUsername(username: String): ImportedAccessRef? =
        username.trim().takeIf { it.isNotEmpty() }
            ?.let { subscriberAccessRepository.findByUsername(it) }
            ?.let { access ->
                ImportedAccessRef(
                    accessId = access.id,
                    subscriptionId = access.subscriptionId,
                    customerId = access.customerId,
                    planId = access.planId,
                    nasId = access.nasId,
                    macBased = access.authType.macBased,
                )
            }

    @Transactional
    override fun updateAccessFromImport(accessId: UUID, planId: UUID, nasId: UUID?, secret: String?) {
        val access = subscriberAccessRepository.findById(accessId)
            ?: throw ValidationException("Akun jaringan $accessId tidak ditemukan")
        manageAccess.updateAssignment(accessId, UpdateAccessCommand(planId = planId, nasId = nasId))
        // Password hanya diganti bila kolom CSV diisi; akun berbasis MAC tak punya password (MAC = password).
        val newSecret = secret?.trim()?.takeIf { it.isNotEmpty() }
        if (newSecret != null && !access.authType.macBased) {
            manageAccess.resetSecret(accessId, ResetSecretCommand(newSecret))
        }
    }

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

    override fun exportAccesses(): List<AccessExportRef> {
        val accounts = subscriberAccessRepository.findAll()
        if (accounts.isEmpty()) return emptyList()
        // Nama BRAS di-resolusi lewat satu peta (hindari N+1); akun tanpa BRAS → nama null.
        val nasNames = nasRepository.findAll().associate { it.id to it.name }
        return accounts.map { access ->
            AccessExportRef(
                username = access.username,
                authType = access.authType.name,
                subscriptionId = access.subscriptionId,
                customerId = access.customerId,
                nasName = access.nasId?.let { nasNames[it] },
            )
        }
    }

    override fun activeSubscriberLiveness(): List<SubscriberPppoeLiveness> {
        // Ambang basi dihitung sekali per panggilan agar semua sesi dinilai pada garis
        // waktu yang sama. Jam nyata dipakai di sini (bukan disuntik): pengujian membuat
        // sesi "sangat basi" (lastSeenAt jauh di masa lalu) atau "segar" agar putusannya
        // deterministik tanpa perlu mengendalikan waktu.
        val cutoff = Instant.now().minus(sessionStaleAfter)
        return radiusSessionRepository.findAllForActiveAccounts().map { session ->
            SubscriberPppoeLiveness(
                customerId = session.customerId,
                username = session.username,
                // Poll BRAS hanya melaporkan sesi hidup; sesi yang berakhir menghilang dari
                // radacct tanpa ditandai offline. Maka baris ber-online=true yang tak segar
                // lagi diperlakukan sebagai putus — inilah dasar deteksi "hilang, bukan mati".
                online = session.online && !session.lastSeenAt.isBefore(cutoff),
                lastSeenAt = session.lastSeenAt,
            )
        }
    }
}

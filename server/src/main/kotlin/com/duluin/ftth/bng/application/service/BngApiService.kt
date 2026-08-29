package com.duluin.ftth.bng.application.service

import com.duluin.ftth.bng.AccessExportRef
import com.duluin.ftth.bng.BngApi
import com.duluin.ftth.bng.VoucherActionRef
import com.duluin.ftth.bng.VoucherCredentialRef
import com.duluin.ftth.bng.VoucherCredentialSpec
import com.duluin.ftth.bng.VoucherSessionRef
import com.duluin.ftth.bng.ImportedAccessRef
import com.duluin.ftth.bng.PppSecretRef
import com.duluin.ftth.bng.ProvisionAccessSpec
import com.duluin.ftth.bng.ProvisionedAccessRef
import com.duluin.ftth.bng.SubscriberPppoeLiveness
import com.duluin.ftth.bng.SubscriberPppoeRef
import com.duluin.ftth.bng.SubscriberSessionRef
import com.duluin.ftth.bng.application.port.inbound.ManageSubscriberAccessUseCase
import com.duluin.ftth.bng.application.port.inbound.ProvisionAccessCommand
import com.duluin.ftth.bng.application.port.inbound.ResetSecretCommand
import com.duluin.ftth.bng.application.port.inbound.UpdateAccessCommand
import com.duluin.ftth.bng.application.port.outbound.NasAreaCoverageRepository
import com.duluin.ftth.bng.application.port.outbound.NasRepository
import com.duluin.ftth.bng.application.port.outbound.RadiusAccountingReadPort
import com.duluin.ftth.bng.application.port.outbound.RadiusSessionRepository
import com.duluin.ftth.bng.application.port.outbound.BngActionRepository
import com.duluin.ftth.bng.application.port.outbound.RouterOsPort
import com.duluin.ftth.bng.application.port.outbound.SubscriberAccessRepository
import com.duluin.ftth.bng.domain.model.AuthType
import com.duluin.ftth.bng.domain.model.RadiusSession
import com.duluin.ftth.bng.domain.model.SubscriberAccess
import com.duluin.ftth.catalog.CatalogApi
import com.duluin.ftth.common.domain.error.ValidationException
import com.duluin.ftth.common.security.SecretCipher
import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.tenancy.TenantApi
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
    private val bngActionRepository: BngActionRepository,
    private val radiusAccounting: RadiusAccountingReadPort,
    private val secretCipher: SecretCipher,
    private val tenantApi: TenantApi,
    /**
     * Ambang basi sesi: baris `radacct` ber-`online=true` yang tak diperbarui melebihi
     * durasi ini dianggap sudah putus. Dipilih 3 menit — beberapa kali interval poll BRAS
     * (umumnya 30–60 dtk), jadi satu poll yang terlewat tak langsung memerahkan pelanggan,
     * tapi sesi yang benar-benar berakhir tetap ketahuan cepat.
     */
    @Value("\${ftth.bng.session-stale-after:PT3M}") private val sessionStaleAfter: Duration,
) : BngApi {

    @Transactional
    override fun provisionVoucherCredential(command: VoucherCredentialSpec): VoucherCredentialRef {
        val externalId = requiredVoucherValue(command.externalId, "externalId")
        val username = requiredVoucherValue(command.username, "username")
        val credential = requiredVoucherValue(command.credential, "credential")
        val plan = catalogApi.findActiveHotspotPlan(command.planId)
            ?: throw ValidationException("Paket HOTSPOT aktif ${command.planId} tidak ditemukan atau tidak eligible untuk voucher")
        nasRepository.findById(command.nasId)
            ?: throw ValidationException("BRAS ${command.nasId} tidak ditemukan")
        val actions = bngActionRepository.findVoucherActions(externalId)
        actions.firstOrNull { it.action == com.duluin.ftth.bng.domain.model.BngActionType.PROVISION }
            ?.let { return voucherRef(externalId, username, actions) }
        bngActionRepository.save(
            com.duluin.ftth.bng.domain.model.BngAction.voucherProvision(
                tenantId = TenantContext.tenantId(), nasId = command.nasId, username = username,
                externalId = externalId, credentialCiphertext = secretCipher.encrypt(credential),
                groupname = com.duluin.ftth.bng.domain.model.RadiusGroups.normal(plan.planId),
            ),
        )
        return voucherRef(externalId, username, bngActionRepository.findVoucherActions(externalId))
    }

    @Transactional
    override fun revokeVoucherCredential(externalId: String): VoucherCredentialRef? {
        val id = requiredVoucherValue(externalId, "externalId")
        val actions = bngActionRepository.findVoucherActions(id)
        val provision = actions.firstOrNull { it.action == com.duluin.ftth.bng.domain.model.BngActionType.PROVISION } ?: return null
        if (actions.none { it.action == com.duluin.ftth.bng.domain.model.BngActionType.DEPROVISION }) {
            bngActionRepository.save(com.duluin.ftth.bng.domain.model.BngAction.voucherDeprovision(
                tenantId = TenantContext.tenantId(), nasId = provision.nasId, username = provision.username, externalId = id,
            ))
        }
        return voucherRef(id, provision.username, bngActionRepository.findVoucherActions(id))
    }

    @Transactional
    override fun disconnectVoucherCredential(externalId: String): VoucherActionRef? {
        val id = requiredVoucherValue(externalId, "externalId")
        val actions = bngActionRepository.findVoucherActions(id)
        val provision = actions.firstOrNull { it.action == com.duluin.ftth.bng.domain.model.BngActionType.PROVISION } ?: return null
        actions.firstOrNull { it.action == com.duluin.ftth.bng.domain.model.BngActionType.DISCONNECT }
            ?.let { return it.toVoucherActionRef() }
        return bngActionRepository.save(com.duluin.ftth.bng.domain.model.BngAction.voucherDisconnect(
            tenantId = TenantContext.tenantId(), nasId = provision.nasId, username = provision.username, externalId = id,
        )).toVoucherActionRef()
    }

    override fun findVoucherCredential(externalId: String): VoucherCredentialRef? {
        val id = requiredVoucherValue(externalId, "externalId")
        val actions = bngActionRepository.findVoucherActions(id)
        val provision = actions.firstOrNull { it.action == com.duluin.ftth.bng.domain.model.BngActionType.PROVISION } ?: return null
        return voucherRef(id, provision.username, actions)
    }

    override fun findVoucherSession(externalId: String): VoucherSessionRef? {
        val id = requiredVoucherValue(externalId, "externalId")
        val provision = bngActionRepository.findVoucherActions(id)
            .firstOrNull { it.action == com.duluin.ftth.bng.domain.model.BngActionType.PROVISION } ?: return null
        if (!radiusAccounting.isConfigured()) return null
        val tenantId = TenantContext.tenantId()
        val tenantCode = tenantApi.findById(tenantId)?.slug ?: return null
        val session = radiusAccounting.activeSessions(tenantId, tenantCode).firstOrNull { it.username == provision.username }
            ?: return VoucherSessionRef(id, false, null, null, null, null, null, null)
        return VoucherSessionRef(
            externalId = id,
            online = session.online,
            nasId = provision.nasId,
            framedIp = session.framedIp,
            sessionId = session.sessionId,
            callingStationId = session.callingStationId,
            startedAt = session.uptimeSeconds?.let { Instant.now().minusSeconds(it) },
            lastSeenAt = Instant.now(),
            inputBytes = session.inOctets,
            outputBytes = session.outOctets,
        )
    }

    private fun requiredVoucherValue(value: String, field: String): String =
        value.trim().takeIf { it.isNotEmpty() } ?: throw ValidationException("Voucher $field wajib diisi")

    private fun voucherRef(externalId: String, username: String, actions: List<com.duluin.ftth.bng.domain.model.BngAction>): VoucherCredentialRef {
        val provision = actions.firstOrNull { it.action == com.duluin.ftth.bng.domain.model.BngActionType.PROVISION }
        val revoke = actions.firstOrNull { it.action == com.duluin.ftth.bng.domain.model.BngActionType.DEPROVISION }
        val state = when {
            revoke != null && revoke.status != com.duluin.ftth.bng.domain.model.BngActionStatus.FAILED -> "REVOKED"
            provision == null -> "UNKNOWN"
            provision.status == com.duluin.ftth.bng.domain.model.BngActionStatus.COMPLETED -> "PROVISIONED"
            provision.status == com.duluin.ftth.bng.domain.model.BngActionStatus.FAILED -> "FAILED"
            else -> "PENDING"
        }
        return VoucherCredentialRef(externalId, username, state, provision?.toVoucherActionRef(), revoke?.toVoucherActionRef())
    }

    private fun com.duluin.ftth.bng.domain.model.BngAction.toVoucherActionRef() =
        VoucherActionRef(id, status.name, detail, requestedAt, completedAt)

    override fun resolveNasForArea(areaId: UUID): UUID? = coverageRepository.findNasIdByAreaId(areaId)

    override fun resolveNasByName(name: String): UUID? =
        name.trim().takeIf { it.isNotEmpty() }?.let { nasRepository.findByNameIgnoreCase(it)?.id }

    override fun hasNas(nasId: UUID): Boolean = nasRepository.findById(nasId) != null

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

    /**
     * Satu pelanggan bisa punya beberapa akun (mis. unit kedua) — yang mewakilinya adalah akun
     * yang sesinya benar-benar hidup; kalau tak ada, yang pertama (repositori mengurutkan
     * username, jadi deterministik). Diekstrak agar jalur satu-pelanggan dan jalur batch tak
     * melenceng memilih akun berbeda untuk pelanggan yang sama. [accounts] tak boleh kosong.
     *
     * Kesegaran ikut dinilai di sini: kalau tidak, sesi basi milik unit kedua bisa "menang"
     * atas sesi unit pertama yang sungguh online.
     */
    private fun chooseAccount(
        accounts: List<SubscriberAccess>,
        now: Instant,
        sessionOf: (UUID) -> RadiusSession?,
    ): SubscriberAccess =
        accounts.firstOrNull { sessionOf(it.id)?.isLiveAt(now, sessionStaleAfter) == true } ?: accounts.first()

    override fun findSubscriberSession(customerId: UUID): SubscriberSessionRef? {
        val accounts = subscriberAccessRepository.findByCustomerId(customerId)
        if (accounts.isEmpty()) return null

        val now = Instant.now()
        val sessions = accounts.associate { it.id to radiusSessionRepository.findBySubscriberAccessId(it.id) }
        val chosen = chooseAccount(accounts, now) { sessions[it] }
        val session = sessions[chosen.id]
        // Sumber NAS: dari sesi terkini bila ada (yang benar-benar dipakai login),
        // kalau belum pernah terpantau jatuh ke NAS yang ditugaskan pada akun.
        val nasId = session?.nasId ?: chosen.nasId
        // Sama seperti [activeSubscriberLiveness]: baris online yang tak segar lagi = putus.
        val live = session?.isLiveAt(now, sessionStaleAfter) == true

        return SubscriberSessionRef(
            subscriberAccessId = chosen.id,
            username = chosen.username,
            accessStatus = chosen.status.name,
            rateProfileName = catalogApi.findPlanNetwork(chosen.planId)?.name,
            online = live,
            // Milik sesi berjalan hanya bermakna selama sesinya hidup — IP & uptime sesi yang
            // sudah tutup dipajang bersebelahan dengan badge "Offline" cuma memancing salah baca.
            framedIp = session?.framedIp?.takeIf { live },
            nasId = nasId,
            nasName = nasId?.let { nasRepository.findById(it)?.name },
            nasIp = session?.nasIp,
            uptimeSeconds = session?.uptimeSeconds?.takeIf { live },
            startedAt = session?.startedAt?.takeIf { live },
            lastSeenAt = session?.lastSeenAt,
        )
    }

    override fun findPppoeByCustomerIds(customerIds: Set<UUID>): Map<UUID, SubscriberPppoeRef> {
        if (customerIds.isEmpty()) return emptyMap()
        val accounts = subscriberAccessRepository.findByCustomerIds(customerIds)
        if (accounts.isEmpty()) return emptyMap()
        // Dua query untuk berapa pun pelanggan: akun sekali, sesinya sekali.
        val sessions = radiusSessionRepository.findBySubscriberAccessIds(accounts.map { it.id })
        // Satu jam untuk seluruh peta: dua pelanggan bertetangga tak boleh dinilai pada
        // ambang basi yang berbeda hanya karena barisnya diproses berselang milidetik.
        val now = Instant.now()

        return accounts.groupBy { it.customerId }.mapValues { (customerId, owned) ->
            val chosen = chooseAccount(owned, now) { sessions[it] }
            val session = sessions[chosen.id]
            val live = session?.isLiveAt(now, sessionStaleAfter) == true
            SubscriberPppoeRef(
                customerId = customerId,
                username = chosen.username,
                online = live,
                framedIp = session?.framedIp?.takeIf { live },
            )
        }
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
                framedIp = access.framedIp,
            )
        }
    }

    override fun activeSubscriberLiveness(): List<SubscriberPppoeLiveness> {
        // Ambang basi dihitung sekali per panggilan agar semua sesi dinilai pada garis
        // waktu yang sama. Jam nyata dipakai di sini (bukan disuntik): pengujian membuat
        // sesi "sangat basi" (lastSeenAt jauh di masa lalu) atau "segar" agar putusannya
        // deterministik tanpa perlu mengendalikan waktu.
        val now = Instant.now()
        return radiusSessionRepository.findAllForActiveAccounts().map { session ->
            SubscriberPppoeLiveness(
                customerId = session.customerId,
                username = session.username,
                // Baris ber-online=true yang tak segar lagi diperlakukan sebagai putus —
                // inilah dasar deteksi "hilang, bukan mati".
                online = session.isLiveAt(now, sessionStaleAfter),
                lastSeenAt = session.lastSeenAt,
            )
        }
    }
}

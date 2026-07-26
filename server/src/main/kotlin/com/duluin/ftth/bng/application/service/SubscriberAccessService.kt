package com.duluin.ftth.bng.application.service

import com.duluin.ftth.bng.application.port.inbound.ControlSubscriberAccessUseCase
import com.duluin.ftth.bng.application.port.inbound.ManageSubscriberAccessUseCase
import com.duluin.ftth.bng.application.port.inbound.ProvisionAccessCommand
import com.duluin.ftth.bng.application.port.inbound.ResetSecretCommand
import com.duluin.ftth.bng.application.port.inbound.SubscriberAccessView
import com.duluin.ftth.bng.application.port.inbound.UpdateAccessCommand
import com.duluin.ftth.bng.application.port.outbound.NasRepository
import com.duluin.ftth.bng.application.port.outbound.RateProfileRepository
import com.duluin.ftth.bng.application.port.outbound.SubscriberAccessRepository
import com.duluin.ftth.bng.domain.model.AccessStatus
import com.duluin.ftth.bng.domain.model.Nas
import com.duluin.ftth.bng.domain.model.RateProfile
import com.duluin.ftth.bng.domain.model.SubscriberAccess
import com.duluin.ftth.common.domain.error.ConflictException
import com.duluin.ftth.common.domain.error.NotFoundException
import com.duluin.ftth.common.infrastructure.audit.AuditRecorder
import com.duluin.ftth.common.security.CurrentUserProvider
import com.duluin.ftth.customer.CustomerApi
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Kelola identitas jaringan (akun PPPoE) pelanggan — data (provisi/ganti/reset/hapus)
 * sekaligus kendali jaringan (isolir/pulih/Reset Login).
 *
 * Langganan divalidasi lewat [CustomerApi] — kontrak publik module customer — bukan
 * dengan menembus internalnya, jadi batas antar-module terjaga. Status awal akun
 * mengikuti status langganan; sinkronisasi selanjutnya digerakkan event daur hidup
 * langganan (lihat [SubscriberAccessLifecycle]). Perintah nyata ke BRAS (memutus/
 * mengubah sesi) diantre lewat [BngActionService] dan dieksekusi collector jalur turun.
 */
@Service
@Transactional
class SubscriberAccessService(
    private val subscriberAccessRepository: SubscriberAccessRepository,
    private val rateProfileRepository: RateProfileRepository,
    private val nasRepository: NasRepository,
    private val customerApi: CustomerApi,
    private val currentUser: CurrentUserProvider,
    private val auditor: AuditRecorder,
    private val bngActions: BngActionService,
) : ManageSubscriberAccessUseCase, ControlSubscriberAccessUseCase {

    @Transactional(readOnly = true)
    override fun listForCustomer(customerId: UUID): List<SubscriberAccessView> =
        subscriberAccessRepository.findByCustomerId(customerId).toViews()

    @Transactional(readOnly = true)
    override fun listForSubscription(subscriptionId: UUID): List<SubscriberAccessView> =
        subscriberAccessRepository.findBySubscriptionId(subscriptionId).toViews()

    @Transactional(readOnly = true)
    override fun get(id: UUID): SubscriberAccessView = listOf(require(id)).toViews().first()

    override fun provision(command: ProvisionAccessCommand): SubscriberAccessView {
        val subscription = customerApi.findSubscription(command.subscriptionId)
            ?: throw NotFoundException("Langganan ${command.subscriptionId} tidak ditemukan")
        if (subscriberAccessRepository.existsBySubscriptionId(subscription.id)) {
            throw ConflictException("Langganan ini sudah punya akun jaringan")
        }
        val username = command.username.trim()
        subscriberAccessRepository.findByUsername(username)?.let {
            throw ConflictException("Username PPPoE '$username' sudah dipakai")
        }
        val profile = requireProfile(command.rateProfileId)
        val nas = command.nasId?.let { requireNas(it) }

        val access = subscriberAccessRepository.save(
            SubscriberAccess.create(
                tenantId = currentUser.current().tenantId,
                subscriptionId = subscription.id,
                customerId = subscription.customerId,
                username = command.username,
                secret = command.secret,
                rateProfileId = profile.id,
                nasId = nas?.id,
                status = initialStatus(subscription.status),
            ),
        )
        auditor.record(
            "bng.access.provisioned", "SubscriberAccess", access.id, access.tenantId,
            mapOf("username" to access.username, "subscription" to access.subscriptionId.toString()),
        )
        return access.toView(profile.name, nas?.name)
    }

    override fun updateAssignment(id: UUID, command: UpdateAccessCommand): SubscriberAccessView {
        val access = require(id)
        val previousProfileId = access.rateProfileId
        val profile = requireProfile(command.rateProfileId)
        val nas = command.nasId?.let { requireNas(it) }
        access.assignProfile(profile.id)
        access.moveToNas(nas?.id)
        val saved = subscriberAccessRepository.save(access)
        // Paket berubah pada akun aktif → dorong CoA agar kecepatan sesi hidup ikut
        // berubah tanpa memutusnya. No-op bila akun belum di BRAS (ditangani service).
        if (previousProfileId != profile.id && saved.status == AccessStatus.ACTIVE) {
            val user = currentUser.current()
            bngActions.enqueueCoa(saved, profile.downMbps, profile.upMbps, user.userId, user.email)
        }
        auditor.record(
            "bng.access.updated", "SubscriberAccess", saved.id, saved.tenantId,
            mapOf("username" to saved.username, "plan" to profile.name),
        )
        return saved.toView(profile.name, nas?.name)
    }

    override fun resetSecret(id: UUID, command: ResetSecretCommand): SubscriberAccessView {
        val access = require(id)
        access.resetSecret(command.secret)
        val saved = subscriberAccessRepository.save(access)
        // Detail sengaja tanpa nilai password — jejak audit tak boleh menyimpan rahasia.
        auditor.record(
            "bng.access.secret_reset", "SubscriberAccess", saved.id, saved.tenantId,
            mapOf("username" to saved.username),
        )
        return listOf(saved).toViews().first()
    }

    override fun delete(id: UUID) {
        val access = require(id)
        subscriberAccessRepository.deleteById(id)
        auditor.record(
            "bng.access.deleted", "SubscriberAccess", id, access.tenantId,
            mapOf("username" to access.username),
        )
    }

    // ---- Kendali jaringan (jalur tulis ke BRAS) ----

    override fun isolate(id: UUID): SubscriberAccessView {
        val access = require(id)
        access.isolate()
        val saved = subscriberAccessRepository.save(access)
        val user = currentUser.current()
        // Isolir "beneran motong": status ISOLATED mengeluarkannya dari sesi yang
        // diharapkan online, DISCONNECT memutus sesi yang masih hidup sekarang.
        bngActions.enqueueDisconnect(saved, user.userId, user.email)
        auditor.record(
            "bng.access.isolated", "SubscriberAccess", saved.id, saved.tenantId,
            mapOf("username" to saved.username),
        )
        return listOf(saved).toViews().first()
    }

    override fun restore(id: UUID): SubscriberAccessView {
        val access = require(id)
        access.activate()
        val saved = subscriberAccessRepository.save(access)
        // Tak perlu perintah: begitu ACTIVE, akun kembali masuk daftar sesi yang
        // diharapkan online dan sesi berikutnya re-auth mengambil profil aktif.
        auditor.record(
            "bng.access.restored", "SubscriberAccess", saved.id, saved.tenantId,
            mapOf("username" to saved.username),
        )
        return listOf(saved).toViews().first()
    }

    override fun resetLogin(id: UUID): SubscriberAccessView {
        val access = require(id)
        if (access.status == AccessStatus.TERMINATED) {
            throw ConflictException("Akun jaringan sudah dihentikan — tidak bisa di-Reset Login")
        }
        val user = currentUser.current()
        // Reset Login tanpa mengubah status: cukup putus sesi agar CPE dial ulang.
        val enqueued = bngActions.enqueueDisconnect(access, user.userId, user.email)
        if (!enqueued) {
            throw ConflictException("Akun belum ditugaskan ke BRAS — tak ada sesi untuk di-reset")
        }
        auditor.record(
            "bng.session.reset", "SubscriberAccess", access.id, access.tenantId,
            mapOf("username" to access.username),
        )
        return listOf(access).toViews().first()
    }

    /**
     * Status akun mengikuti status langganan saat dibuat: langganan aktif/menunggu
     * instalasi menghasilkan akun aktif, langganan terisolir menghasilkan akun
     * terisolir. Langganan yang sudah diakhiri tak boleh dibuatkan akun baru.
     */
    private fun initialStatus(subscriptionStatus: String): AccessStatus = when (subscriptionStatus) {
        "ACTIVE", "PENDING" -> AccessStatus.ACTIVE
        "ISOLATED" -> AccessStatus.ISOLATED
        else -> throw ConflictException("Langganan berstatus $subscriptionStatus tidak bisa dibuatkan akun jaringan")
    }

    private fun require(id: UUID): SubscriberAccess =
        subscriberAccessRepository.findById(id) ?: throw NotFoundException("Akun jaringan $id tidak ditemukan")

    private fun requireProfile(id: UUID): RateProfile =
        rateProfileRepository.findById(id) ?: throw NotFoundException("Paket $id tidak ditemukan")

    private fun requireNas(id: UUID): Nas =
        nasRepository.findById(id) ?: throw NotFoundException("BRAS $id tidak ditemukan")

    /**
     * Meresolusi nama paket & BRAS untuk sekumpulan akun dalam dua query tetap
     * (semua paket + semua BRAS tenant, keduanya himpunan kecil), menghindari
     * lookup per-baris.
     */
    private fun List<SubscriberAccess>.toViews(): List<SubscriberAccessView> {
        if (isEmpty()) return emptyList()
        val profileNames = rateProfileRepository.findAll().associate { it.id to it.name }
        val nasNames = nasRepository.findAll().associate { it.id to it.name }
        return map { it.toView(profileNames[it.rateProfileId], it.nasId?.let(nasNames::get)) }
    }
}

private fun SubscriberAccess.toView(rateProfileName: String?, nasName: String?) = SubscriberAccessView(
    id = id,
    subscriptionId = subscriptionId,
    customerId = customerId,
    username = username,
    authType = authType.name,
    rateProfileId = rateProfileId,
    rateProfileName = rateProfileName,
    nasId = nasId,
    nasName = nasName,
    status = status.name,
)

package com.duluin.ftth.bng.application.service

import com.duluin.ftth.bng.application.port.inbound.ControlSubscriberAccessUseCase
import com.duluin.ftth.bng.application.port.inbound.ManageSubscriberAccessUseCase
import com.duluin.ftth.bng.application.port.inbound.ProvisionAccessCommand
import com.duluin.ftth.bng.application.port.inbound.ResetSecretCommand
import com.duluin.ftth.bng.application.port.inbound.SubscriberAccessView
import com.duluin.ftth.bng.application.port.inbound.UpdateAccessCommand
import com.duluin.ftth.bng.application.port.outbound.AccountingRecordRepository
import com.duluin.ftth.bng.application.port.outbound.NasRepository
import com.duluin.ftth.bng.application.port.outbound.SubscriberAccessRepository
import com.duluin.ftth.bng.domain.model.AccessStatus
import com.duluin.ftth.bng.domain.model.Nas
import com.duluin.ftth.bng.domain.model.SubscriberAccess
import com.duluin.ftth.catalog.CatalogApi
import com.duluin.ftth.catalog.PlanNetworkRef
import com.duluin.ftth.common.domain.error.ConflictException
import com.duluin.ftth.common.domain.error.NotFoundException
import com.duluin.ftth.common.domain.error.ValidationException
import com.duluin.ftth.common.infrastructure.audit.AuditRecorder
import com.duluin.ftth.common.security.CurrentUserProvider
import com.duluin.ftth.customer.CustomerApi
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
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
    private val accountingRecordRepository: AccountingRecordRepository,
    private val catalogApi: CatalogApi,
    private val nasRepository: NasRepository,
    private val customerApi: CustomerApi,
    private val currentUser: CurrentUserProvider,
    private val auditor: AuditRecorder,
    private val bngActions: BngActionService,
) : ManageSubscriberAccessUseCase, ControlSubscriberAccessUseCase {

    private val secureRandom = java.security.SecureRandom()

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
        val plan = requirePlan(command.planId)
        // Paket harus melayani tipe autentikasi yang diminta (Ketersediaan `serviceTypes`
        // paket) — mencegah membuat akun DHCP/Hotspot pada paket yang cuma untuk PPPoE.
        if (command.authType.name !in plan.serviceTypes) {
            throw ConflictException("Paket '${plan.name}' tidak melayani tipe layanan ${command.authType.name}")
        }
        val nas = command.nasId?.let { requireNas(it) }
        // Kredensial login yang dikosongkan operator di-generate server-side (kebijakan onboarding
        // "auto-generate, boleh override"); tipe berbasis MAC memakai MAC-nya sendiri sebagai identitas.
        val (username, secret) = resolveCredentials(command, subscription.customerId)

        // Bangun akun DULU: companion menormalkan identitas (MAC → `AA:BB:...`) & memvalidasi
        // per-tipe, jadi uji keunikan dilakukan atas identitas ternormalkan, bukan input mentah.
        val account = SubscriberAccess.create(
            tenantId = currentUser.current().tenantId,
            subscriptionId = subscription.id,
            customerId = subscription.customerId,
            username = username,
            secret = secret,
            planId = plan.planId,
            nasId = nas?.id,
            status = initialStatus(subscription.status),
            authType = command.authType,
            framedIp = command.framedIp,
        )
        subscriberAccessRepository.findByUsername(account.username)?.let {
            throw ConflictException("Identitas jaringan '${account.username}' sudah dipakai")
        }
        val access = subscriberAccessRepository.save(account)
        // RADIUS jadi pusat: pastikan grup paket ada di BRAS lalu tulis kredensial + keanggotaan
        // akun. No-op bila akun belum ditugaskan ke BRAS. Akun PENDING (langganan masih menunggu
        // instalasi) sengaja BELUM ditulis ke RADIUS — pelanggan baru online saat WO PSB selesai
        // (SubscriberAccessLifecycle.onActivated yang memprovisikannya).
        val user = currentUser.current()
        if (access.status != AccessStatus.PENDING) {
            access.nasId?.let { nasId ->
                bngActions.enqueueSyncGroup(nasId, access.tenantId, plan, user.userId, user.email)
                bngActions.enqueueProvision(access, user.userId, user.email)
            }
        }
        auditor.record(
            "bng.access.provisioned", "SubscriberAccess", access.id, access.tenantId,
            mapOf("username" to access.username, "subscription" to access.subscriptionId.toString()),
        )
        return access.toView(plan, nas?.name, periodUsageMb = null)
    }

    override fun updateAssignment(id: UUID, command: UpdateAccessCommand): SubscriberAccessView {
        val access = require(id)
        val previousPlanId = access.planId
        val previousNasId = access.nasId
        val plan = requirePlan(command.planId)
        val nas = command.nasId?.let { requireNas(it) }
        access.assignPlan(plan.planId)
        access.moveToNas(nas?.id)
        val saved = subscriberAccessRepository.save(access)
        val user = currentUser.current()
        // Selalu pastikan grup + kredensial akun tertulis di BRAS tujuan (bila ada).
        saved.nasId?.let { nasId ->
            bngActions.enqueueSyncGroup(nasId, saved.tenantId, plan, user.userId, user.email)
            bngActions.enqueueProvision(saved, user.userId, user.email)
        }
        // Dilepas dari BRAS (tak ditugaskan ke mana pun) → cabut otorisasinya dari RADIUS.
        //
        // HANYA untuk kasus itu. PINDAH BRAS sengaja TIDAK mencabut apa pun: sejak
        // RADIUS-as-a-service, otorisasi tak tersimpan per-BRAS — satu baris radcheck
        // `{kode-tenant}:{username}` melayani seluruh BRAS tenant. Mencabut "di BRAS lama"
        // berarti menghapus baris yang PROVISION di atas baru saja tulis, dan akun lenyap
        // dari RADIUS: pelanggan yang cuma dipindah pembukuannya jadi tak bisa login sama
        // sekali — dengan layar yang bilang sukses. Tak ada yang menggantung di BRAS lama
        // karena tak pernah ada apa pun yang dititipkan ke sana.
        //
        // Sesi yang masih hidup di BRAS lama juga sengaja dibiarkan: BRAS mana yang benar-
        // benar melayani pelanggan ditentukan topologi fisik, bukan catatan kita. Memutus
        // sesi karena catatan dibetulkan artinya membuat gangguan dari pekerjaan
        // administratif. Sesi berikutnya mendarat di BRAS yang memang mengangkutnya.
        if (previousNasId != null && saved.nasId == null) {
            bngActions.enqueueDeprovisionAt(
                previousNasId, saved.tenantId, saved.username, saved.authType, user.userId, user.email,
            )
        } else if (previousNasId == saved.nasId && previousPlanId != plan.planId &&
            saved.status == AccessStatus.ACTIVE
        ) {
            // Paket berubah pada BRAS yang sama & akun aktif → CoA agar sesi hidup langsung
            // memakai kecepatan baru tanpa memutusnya.
            bngActions.enqueueCoa(saved, plan.downMbps, plan.upMbps, user.userId, user.email)
        }
        auditor.record(
            "bng.access.updated", "SubscriberAccess", saved.id, saved.tenantId,
            mapOf("username" to saved.username, "plan" to plan.name),
        )
        return saved.toView(plan, nas?.name, periodUsageMb = null)
    }

    override fun resetSecret(id: UUID, command: ResetSecretCommand): SubscriberAccessView {
        val access = require(id)
        access.resetSecret(command.secret)
        val saved = subscriberAccessRepository.save(access)
        val user = currentUser.current()
        // Password baru WAJIB didorong ke RADIUS, bukan cuma disimpan di sini. Yang
        // diperiksa saat pelanggan dial adalah baris radcheck; tanpa dorongan ini layar
        // mengabarkan "password sudah diganti" sementara BRAS masih memeriksa yang lama —
        // dan orang yang mengganti password justru sedang menolong pelanggan yang tak
        // bisa masuk, jadi kegagalan diamnya persis di saat paling menyesatkan.
        //
        // Kecuali akun PENDING: instalasinya belum selesai, jadi ia memang belum ada di
        // RADIUS dan tak boleh diadakan lewat pintu belakang ganti password. Ia ditulis
        // saat WO PSB ditutup (SubscriberAccessLifecycle.onActivated), dengan password
        // terbaru — yang barusan disimpan ini.
        if (saved.status != AccessStatus.PENDING) {
            bngActions.enqueueProvision(saved, user.userId, user.email)
        }
        // Detail sengaja tanpa nilai password — jejak audit tak boleh menyimpan rahasia.
        auditor.record(
            "bng.access.secret_reset", "SubscriberAccess", saved.id, saved.tenantId,
            mapOf("username" to saved.username),
        )
        return listOf(saved).toViews().first()
    }

    override fun delete(id: UUID) {
        val access = require(id)
        val user = currentUser.current()
        // Cabut otorisasi RADIUS SEBELUM baris akun hilang. DEPROVISION tak menaut akun
        // (subscriberAccessId null) sehingga selamat dari CASCADE saat baris akun dihapus —
        // penghapusan di BRAS tetap terkirim.
        bngActions.enqueueDeprovision(access, user.userId, user.email)
        subscriberAccessRepository.deleteById(id)
        auditor.record(
            "bng.access.deleted", "SubscriberAccess", id, access.tenantId,
            mapOf("username" to access.username),
        )
    }

    // ---- Kendali jaringan (jalur tulis ke BRAS) ----

    override fun isolate(id: UUID): SubscriberAccessView {
        val access = require(id)
        // Akun PENDING belum pernah dituliskan ke RADIUS (dibuat saat WO PSB belum rampung).
        // Mengisolirnya tak perlu menyentuh RADIUS sama sekali: statusnya saja sudah cukup,
        // dan menuliskannya sekarang justru memberi login pada pelanggan yang kabelnya bahkan
        // belum terpasang. Pemulihannya kelak tetap utuh — jalur pulih menyinkronkan grup
        // paket lebih dulu, jadi ia tak bergantung pada tulisan yang dilewati di sini.
        val wasPending = access.status == AccessStatus.PENDING
        access.isolate()
        val saved = subscriberAccessRepository.save(access)
        val user = currentUser.current()
        // Isolir "beneran motong", tapi TIDAK dengan mencabut loginnya: akun dipindah ke grup
        // isolir lalu sesinya diputus, sehingga dial berikutnya tetap berhasil dan mendarat di
        // halaman tagihan. Pelanggan yang menatap "PPPoE gagal" hanya akan menelepon CS;
        // pelanggan yang melihat tagihannya sendiri bisa langsung membayar.
        if (!wasPending) bngActions.enqueueIsolir(saved, user.userId, user.email)
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
        val user = currentUser.current()
        // Mengembalikan status saja tidak cukup: selama sesi isolirnya belum mati, router masih
        // memegang keanggotaan address-list yang melempar pelanggan ke halaman tagihan. Jadi
        // grup dipulihkan LALU sesi diputus — kedipan beberapa detik, tapi pelanggan yang sudah
        // membayar langsung benar-benar online lagi tanpa menunggu ia menyalakan ulang routernya.
        bngActions.enqueueRestore(saved, catalogApi.findPlanNetwork(saved.planId), user.userId, user.email)
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
     * Status akun mengikuti status langganan saat dibuat: langganan aktif → akun aktif (langsung
     * ditulis ke RADIUS), langganan menunggu instalasi → akun PENDING (belum ditulis ke RADIUS;
     * diaktifkan+diprovisikan saat WO PSB selesai lewat [SubscriberAccessLifecycle.onActivated]),
     * langganan terisolir → akun terisolir. Langganan yang sudah diakhiri tak boleh dibuatkan akun.
     */
    private fun initialStatus(subscriptionStatus: String): AccessStatus = when (subscriptionStatus) {
        "ACTIVE" -> AccessStatus.ACTIVE
        "PENDING" -> AccessStatus.PENDING
        "ISOLATED" -> AccessStatus.ISOLATED
        else -> throw ConflictException("Langganan berstatus $subscriptionStatus tidak bisa dibuatkan akun jaringan")
    }

    /**
     * Kredensial login akhir: dipakai apa adanya bila operator mengisinya, di-generate server-side
     * bila dikosongkan (kebijakan onboarding "auto-generate, boleh override"). Tipe berbasis MAC
     * (DHCP/Static) tak memakai kredensial login — identitasnya MAC (WAJIB diisi operator) dan
     * secret diabaikan domain; keduanya diteruskan apa adanya untuk dinormalkan/divalidasi domain.
     */
    private fun resolveCredentials(command: ProvisionAccessCommand, customerId: UUID): Pair<String, String> {
        if (command.authType.macBased) {
            val mac = command.username?.takeIf { it.isNotBlank() }
                ?: throw ValidationException("Akun ${command.authType.name} butuh MAC address")
            return mac to command.secret.orEmpty()
        }
        val username = command.username?.takeIf { it.isNotBlank() } ?: generateUsername(customerId)
        val secret = command.secret?.takeIf { it.isNotBlank() } ?: generateSecret()
        return username to secret
    }

    /**
     * Username login turunan kode pelanggan (dinormalkan ke pola username domain), dijaga unik
     * dalam tenant (RLS) dengan suffix angka bila kode sudah dipakai akun lain (mis. pelanggan
     * ber-langganan lebih dari satu). Fallback "user" bila kode tak menyisakan karakter valid.
     */
    private fun generateUsername(customerId: UUID): String {
        val base = customerApi.findCustomer(customerId)?.code
            ?.lowercase()?.replace(Regex("[^a-z0-9._@-]"), "")
            ?.takeIf { it.length >= 2 }
            ?: "user"
        if (subscriberAccessRepository.findByUsername(base) == null) return base
        var n = 2
        while (subscriberAccessRepository.findByUsername("$base-$n") != null) n++
        return "$base-$n"
    }

    /** Secret acak 12 karakter (alfabet tanpa karakter mirip) — memenuhi aturan panjang domain. */
    private fun generateSecret(): String =
        (1..12).map { SECRET_ALPHABET[secureRandom.nextInt(SECRET_ALPHABET.length)] }.joinToString("")

    private fun require(id: UUID): SubscriberAccess =
        subscriberAccessRepository.findById(id) ?: throw NotFoundException("Akun jaringan $id tidak ditemukan")

    private fun requirePlan(id: UUID): PlanNetworkRef =
        catalogApi.findPlanNetwork(id) ?: throw NotFoundException("Paket $id tidak ditemukan")

    private fun requireNas(id: UUID): Nas =
        nasRepository.findById(id) ?: throw NotFoundException("BRAS $id tidak ditemukan")

    /**
     * Meresolusi paket & BRAS untuk sekumpulan akun tanpa lookup per-baris: nama BRAS dari
     * satu query (himpunan kecil per tenant), paket dimemo per planId unik lewat katalog
     * (akun umumnya berbagi sedikit paket), dan pemakaian FUP periode berjalan seluruh akun
     * ditarik sekali (satu query batch reset-aware) untuk indikator kuota.
     */
    private fun List<SubscriberAccess>.toViews(): List<SubscriberAccessView> {
        if (isEmpty()) return emptyList()
        val plans = map { it.planId }.distinct().associateWith { catalogApi.findPlanNetwork(it) }
        val nasNames = nasRepository.findAll().associate { it.id to it.name }
        val usage = accountingRecordRepository.usageSince(map { it.id }, currentPeriodStart())
        return map { access ->
            access.toView(plans[access.planId], access.nasId?.let(nasNames::get), usage[access.id]?.toMb())
        }
    }

    /** Awal siklus FUP = hari-1 bulan berjalan di zona sistem (selaras penegak FUP). */
    private fun currentPeriodStart(): Instant =
        LocalDate.now().withDayOfMonth(1).atStartOfDay(ZoneId.systemDefault()).toInstant()
}

/** Alfabet secret auto-generate — tanpa 0/O/1/l/I yang mudah tertukar saat dibaca operator. */
private const val SECRET_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789"

/** Byte → MB desimal (1 MB = 1e6 byte), selaras kuota FUP & Mbps di seluruh sistem. */
private fun Long.toMb(): Long = this / 1_000_000L

private fun SubscriberAccess.toView(plan: PlanNetworkRef?, nasName: String?, periodUsageMb: Long?) =
    SubscriberAccessView(
        id = id,
        subscriptionId = subscriptionId,
        customerId = customerId,
        username = username,
        authType = authType.name,
        framedIp = framedIp,
        planId = planId,
        planName = plan?.name,
        nasId = nasId,
        nasName = nasName,
        status = status.name,
        fupEnabled = plan?.fupEnabled ?: false,
        fupQuotaMb = plan?.fupQuotaMb,
        fupThrottled = fupThrottled,
        periodUsageMb = periodUsageMb,
    )

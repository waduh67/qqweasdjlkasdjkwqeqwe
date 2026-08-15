package com.duluin.ftth.bng.application.service

import com.duluin.ftth.bng.application.port.outbound.BngActionRepository
import com.duluin.ftth.bng.application.port.outbound.NasRepository
import com.duluin.ftth.bng.application.port.outbound.RadiusAccountingReadPort
import com.duluin.ftth.bng.application.port.outbound.RadiusSessionControlPort
import com.duluin.ftth.bng.config.RadiusProperties
import com.duluin.ftth.bng.domain.model.BngAction
import com.duluin.ftth.bng.domain.model.BngActionType
import com.duluin.ftth.bng.domain.model.Nas
import com.duluin.ftth.bng.domain.model.NasReachability
import com.duluin.ftth.bng.domain.model.SessionObservation
import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.tenancy.TenantApi
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Worker jalur-KONTROL sesi RADIUS server-side (RADIUS-as-a-service): mengklaim aksi
 * [BngActionType.SESSION_CONTROL] (DISCONNECT/COA) untuk BRAS yang SERVER jangkau sendiri
 * (reachability ≠ COLLECTOR) lalu menembak DAE langsung ke NAS lewat [RadiusSessionControlPort].
 * BRAS COLLECTOR tetap dilayani agent on-prem (jalur turun collector) — pembelahan-klaim.
 *
 * Cermin [RadiusProvisioningDispatcher]: berjalan di luar konteks request, tenant dipasang
 * satu per satu lewat [TenantContext.runAs]; kegagalan satu tenant tak menghentikan lainnya.
 * Digerbangi [RadiusAccountingReadPort.isConfigured] — jalur DIRECT perlu membaca `radacct`
 * untuk meresolusi Acct-Session-Id/NAS-IP sesi sasaran; tanpa radius-db (dev/test) putaran
 * dilewati dan aksi tetap PENDING sampai dikonfigurasi.
 *
 * Asumsi single-instance sama seperti provisioning: klaim tak memakai DISPATCHED/locking,
 * mengandalkan `@Scheduled` fixedDelay yang non-reentrant.
 */
@Component
class RadiusSessionControlDispatcher(
    private val tenantApi: TenantApi,
    private val radacct: RadiusAccountingReadPort,
    private val runner: RadiusSessionControlRunner,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${ftth.radius.dispatch-interval:PT10S}")
    fun dispatch() {
        if (!radacct.isConfigured()) return
        tenantApi.findActiveTenantIds().forEach { tenantId ->
            runCatching { TenantContext.runAs(tenantId) { runner.run(tenantId) } }
                .onFailure { log.warn("Kontrol sesi RADIUS tenant {} gagal: {}", tenantId, it.message) }
        }
    }
}

/**
 * Pekerja kontrol sesi satu tenant dalam transaksinya sendiri — komponen terpisah dari
 * [RadiusSessionControlDispatcher] agar proxy `@Transactional` Spring berlaku (REQUIRES_NEW
 * mengurung kegagalan ke satu tenant). Cermin [RadiusProvisioningRunner].
 *
 * Tiga jalur per reachability BRAS (keputusan terkunci RADIUS-as-a-service):
 *  - **DIRECT**: tembak DAE ke IP publik NAS. DISCONNECT tanpa sesi = sudah tercapai
 *    (COMPLETED polos); COA tanpa sesi = degradasi (tak bisa ubah sesi mati, tapi rate baru
 *    sudah tertulis di grup → berlaku saat login ulang).
 *  - **VPN**: tembak DAE ke IP OVERLAY NAS (`nas.address` = overlay IP) — jalur & penanganan
 *    sesi IDENTIK DIRECT. Syaratnya server ter-rute ke jaringan overlay (server ko-lokasi hub
 *    VPN). Auto-resolve overlay IP dari peer VPN tertaut = enhancement lanjutan (butuh VpnApi).
 *  - **NONE**: tak terjangkau → degradasi anggun.
 *
 * Sesi `radacct` dibaca MALAS: hanya bila ada aksi DIRECT/VPN (jalur degradasi NONE tak butuh sesi).
 */
@Component
class RadiusSessionControlRunner(
    private val tenantApi: TenantApi,
    private val nasRepository: NasRepository,
    private val bngActionRepository: BngActionRepository,
    private val radacct: RadiusAccountingReadPort,
    private val sessionControl: RadiusSessionControlPort,
    private val props: RadiusProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun run(tenantId: UUID) = execute(tenantId, Instant.now())

    /**
     * Eksekusi antrean kontrol sesi tenant [tenantId] pada waktu [now]. Dipisah dari [run] agar
     * bisa diuji dengan jam tetap. Hanya BRAS yang server jangkau sendiri (reachability ≠
     * COLLECTOR) yang diklaim di sini; BRAS COLLECTOR dilayani agent on-prem.
     */
    fun execute(tenantId: UUID, now: Instant) {
        val serverNas = nasRepository.findAll()
            .filter { it.enabled && it.reachability != NasReachability.COLLECTOR }
            .associateBy { it.id }
        if (serverNas.isEmpty()) return

        val pending = bngActionRepository.findServerSessionControlPending(serverNas.keys, props.batchSize)
        if (pending.isEmpty()) return

        val waiting = waitingForProvisioning(pending)
        val ready = pending.filterNot { it.subscriberAccessId in waiting }
        if (ready.isEmpty()) return

        // Sesi dibutuhkan jalur DAE langsung DIRECT/VPN (untuk Acct-Session-Id/NAS-IP & cek sesi hidup).
        val sessions: Map<String, SessionObservation> =
            if (ready.any { serverNas[it.nasId]?.reachability.firesDae() }) {
                val slug = tenantApi.findById(tenantId)?.slug ?: run {
                    log.warn("Tenant {} tak punya slug — kontrol sesi RADIUS dilewati", tenantId)
                    return
                }
                radacct.activeSessions(tenantId, slug).associateBy { it.username }
            } else {
                emptyMap()
            }

        ready.forEach { action ->
            val nas = serverNas[action.nasId] ?: return@forEach
            executeOne(action, nas, sessions, now)
        }
    }

    /**
     * Akun (dari [pending]) yang otorisasi barunya belum sampai ke radius-db — perintah sesinya
     * DITAHAN, dibiarkan PENDING agar diambil putaran berikutnya.
     *
     * Isolir, pemulihan, dan FUP semuanya mengantre sepasang perintah: ganti grup dulu, baru
     * putus/CoA. Tapi yang mengerjakannya dua worker berbeda pada selang yang sama, jadi tanpa
     * penahan ini pemutusan bisa mendahului pergantian grup: pelanggan terputus, CPE-nya dial
     * ulang dalam hitungan detik, dan RADIUS masih menyambutnya dengan grup LAMA — pelanggan
     * yang baru diisolir kembali online dengan kecepatan penuh, atau yang baru membayar tetap
     * terkurung di halaman tagihan. Menunda satu putaran (10 detik) jauh lebih murah daripada
     * salah menaruh pelanggan.
     */
    private fun waitingForProvisioning(pending: List<BngAction>): Set<UUID> =
        bngActionRepository.findAccessIdsWithPendingProvisioning(pending.mapNotNull { it.subscriberAccessId })

    private fun executeOne(action: BngAction, nas: Nas, sessions: Map<String, SessionObservation>, now: Instant) {
        try {
            when (nas.reachability) {
                // DIRECT (IP publik) & VPN (IP overlay) sama-sama menembak DAE ke nas.address.
                NasReachability.DIRECT, NasReachability.VPN -> fireDae(action, nas, sessions)
                NasReachability.NONE -> action.completeWithNote(degradeNote(action, nas, "BRAS tak terjangkau server"))
                // Klaim hanya BRAS non-COLLECTOR — cabang ini defensif, tak akan tercapai.
                NasReachability.COLLECTOR -> return
            }
            bngActionRepository.save(action)
        } catch (ex: Exception) {
            failOrRetry(action, now, ex)
        }
    }

    /**
     * Jalur DAE langsung (DIRECT/VPN): tembak DAE ke [Nas.address] — IP publik untuk DIRECT,
     * IP overlay untuk VPN. Sesi mati ditangani sesuai jenis aksi (idempoten/degradasi).
     */
    private fun fireDae(action: BngAction, nas: Nas, sessions: Map<String, SessionObservation>) {
        val host = nas.address?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("BRAS ${nas.name}: alamat NAS (tujuan DAE) belum diisi")
        val secret = nas.coaSecret?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("BRAS ${nas.name}: Secret CoA (DAE) belum diisi")
        val session = sessions[action.username]
        when (action.action) {
            BngActionType.DISCONNECT -> if (session == null) {
                // Tak ada sesi di radacct = target tercapai; jangan kirim paket, jangan gagalkan.
                action.complete()
            } else {
                sessionControl.disconnect(host, secret, action.username, session.sessionId, session.nasIp, identifierFor(action))
                action.complete()
            }
            BngActionType.COA -> {
                val down = action.downMbps
                val up = action.upMbps
                require(down != null && up != null) { "CoA ${action.username} butuh downMbps & upMbps" }
                if (session == null) {
                    // Sesi mati: tak bisa CoA, tapi rate baru sudah di grup → berlaku saat login ulang.
                    action.completeWithNote("CoA ${action.username} tak dikirim: sesi tak aktif di ${nas.name}; berlaku saat login ulang")
                } else {
                    sessionControl.changeRate(host, secret, action.username, down, up, session.sessionId, identifierFor(action))
                    action.complete()
                }
            }
            // findServerSessionControlPending hanya mengembalikan SESSION_CONTROL — defensif.
            BngActionType.PROVISION, BngActionType.DEPROVISION, BngActionType.SYNC_GROUP -> return
        }
    }

    private fun degradeNote(action: BngAction, nas: Nas, reason: String): String =
        "${action.action} ${action.username} di ${nas.name} ditunda: $reason; berlaku saat sesi login ulang"

    /**
     * Gagal transien vs menyerah, sama seperti provisioning: selama aksi belum melewati
     * [RadiusProperties.maxRetry] biarkan PENDING agar diulang (NAS mungkin sesaat bisu);
     * setelah itu tandai FAILED agar tak mengulang selamanya. Sebab selalu direkam ke `detail`.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun failOrRetry(action: BngAction, now: Instant, ex: Exception) {
        if (Duration.between(action.requestedAt, now) > props.maxRetry) {
            action.fail(ex.message)
            log.warn("Kontrol sesi {} (aksi {}) menyerah setelah {}: {}", action.username, action.id, props.maxRetry, ex.message)
        } else {
            action.retryLater(ex.message)
            log.info("Kontrol sesi {} (aksi {}) gagal transien, akan diulang: {}", action.username, action.id, ex.message)
        }
        bngActionRepository.save(action)
    }

    // Identifier RADIUS diturunkan dari id aksi agar kiriman ulang (at-least-once) membawa
    // identifier sama — sejalan idempotensi antrean.
    private fun identifierFor(action: BngAction): Int = action.id.hashCode() and 0xFF
}

/**
 * Reachability yang server layani dengan DAE langsung ke `nas.address`: DIRECT (IP publik) &
 * VPN (IP overlay). NONE degradasi, COLLECTOR dilayani agent — keduanya tak menembak dari sini.
 */
private fun NasReachability?.firesDae(): Boolean =
    this == NasReachability.DIRECT || this == NasReachability.VPN

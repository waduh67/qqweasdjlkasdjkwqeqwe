package com.duluin.ftth.bng.application.service

import com.duluin.ftth.bng.application.port.outbound.BngActionRepository
import com.duluin.ftth.bng.application.port.outbound.RadiusProvisioningPort
import com.duluin.ftth.bng.application.port.outbound.SubscriberAccessRepository
import com.duluin.ftth.bng.config.RadiusProperties
import com.duluin.ftth.bng.domain.model.BngAction
import com.duluin.ftth.bng.domain.model.BngActionType
import com.duluin.ftth.bng.domain.model.RadiusGroups
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
 * Worker jalur-TULIS RADIUS (RADIUS-as-a-service): mengklaim aksi
 * [BngActionType.PROVISIONING] (PROVISION/DEPROVISION/SYNC_GROUP) yang diantre modul bng
 * lalu mengeksekusinya langsung ke radius-db platform lewat [RadiusProvisioningPort].
 * Menggantikan jalur lama yang menitipkan provisioning ke collector on-prem — collector
 * tak punya rute ke radius-db internal.
 *
 * Berjalan di luar konteks request, jadi tenant dipasang satu per satu lewat
 * [TenantContext.runAs] — sama seperti [FupScheduler] & scheduler penagihan/CPE. Kegagalan
 * satu tenant tidak menghentikan tenant lain. Bila radius-db belum dikonfigurasi
 * ([RadiusProvisioningPort.isConfigured] = false), putaran dilewati dan aksi tetap menumpuk
 * PENDING sampai dikonfigurasi (dev/test boot tanpa radius-db).
 *
 * Asumsi single-instance: klaim tak memakai status DISPATCHED/locking, mengandalkan
 * `@Scheduled` fixedDelay yang non-reentrant. Bila kelak di-scale-out, klaim perlu
 * lock baris (mis. SKIP LOCKED).
 */
@Component
class RadiusProvisioningDispatcher(
    private val tenantApi: TenantApi,
    private val radius: RadiusProvisioningPort,
    private val runner: RadiusProvisioningRunner,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${ftth.radius.dispatch-interval:PT10S}")
    fun dispatch() {
        if (!radius.isConfigured()) return
        tenantApi.findActiveTenantIds().forEach { tenantId ->
            runCatching { TenantContext.runAs(tenantId) { runner.run(tenantId) } }
                .onFailure { log.warn("Provisioning RADIUS tenant {} gagal: {}", tenantId, it.message) }
        }
    }
}

/**
 * Pekerja provisioning satu tenant dalam transaksinya sendiri.
 *
 * Komponen terpisah dari [RadiusProvisioningDispatcher], bukan method privat: `@Transactional`
 * Spring berlaku lewat proxy, jadi pemanggilan dari dalam kelas yang sama tak akan dibungkus
 * transaksi. REQUIRES_NEW mengurung kegagalan ke satu tenant.
 *
 * Tulisan ke radius-db (lewat [RadiusProvisioningPort]) berjalan di transaksi datasource
 * TERPISAH; penandaan status aksi di sini di datasource aplikasi. Keduanya tak XA, tapi aman:
 * eksekusi dulu → baru tandai COMPLETED, jadi crash di antaranya meninggalkan aksi PENDING
 * yang diulang (idempoten, at-least-once).
 */
@Component
class RadiusProvisioningRunner(
    private val tenantApi: TenantApi,
    private val bngActionRepository: BngActionRepository,
    private val subscriberAccessRepository: SubscriberAccessRepository,
    private val radius: RadiusProvisioningPort,
    private val props: RadiusProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun run(tenantId: UUID) = execute(tenantId, Instant.now())

    /**
     * Eksekusi antrean provisioning tenant [tenantId] pada waktu [now]. Dipisah dari [run]
     * agar bisa diuji dengan jam tetap. Kode tenant (`slug`) diresolusi sekali lalu di-prefix
     * ke username tiap aksi (`"{slug}:{username}"`) — kunci isolasi multi-tenant di radius-db.
     */
    fun execute(tenantId: UUID, now: Instant) {
        val slug = tenantApi.findById(tenantId)?.slug ?: run {
            log.warn("Tenant {} tak punya slug — provisioning RADIUS dilewati", tenantId)
            return
        }
        bngActionRepository.findServerProvisioningPending(props.batchSize)
            .forEach { action -> executeOne(tenantId, slug, action, now) }
    }

    private fun executeOne(tenantId: UUID, slug: String, action: BngAction, now: Instant) {
        try {
            when (action.action) {
                BngActionType.PROVISION -> {
                    // Grup isolir milik PLATFORM — tak pernah lahir dari katalog siapa pun, jadi
                    // tak ada SYNC_GROUP yang mendahuluinya seperti pada grup paket. Dipastikan
                    // ada tepat sebelum akun diikutkan ke dalamnya, bukan sekali saat boot:
                    // begitu kelak radius-db di-shard per tenant, grup ini harus ada di shard
                    // yang sedang ditulis — dan sebuah shard yang sesaat mati tak boleh
                    // meninggalkan platform tanpa grup isolir selamanya.
                    if (requireGroup(action) == RadiusGroups.ISOLIR) {
                        radius.ensureIsolirGroup(tenantId, props.isolirRateLimit, props.isolirAddressList)
                    }
                    radius.provision(
                        tenantId, identity(slug, action), resolvePassword(action), requireGroup(action),
                        resolveFramedIp(action),
                    )
                }
                BngActionType.DEPROVISION -> radius.deprovision(tenantId, identity(slug, action))
                BngActionType.SYNC_GROUP -> radius.syncGroup(
                    tenantId,
                    requireGroup(action),
                    requireRateLimit(action),
                    action.simultaneousUse,
                    action.fupGroupname,
                    action.fupRateLimit,
                )
                // findServerProvisioningPending hanya mengembalikan tipe PROVISIONING — defensif.
                BngActionType.DISCONNECT, BngActionType.COA -> return
            }
            action.complete()
            bngActionRepository.save(action)
        } catch (ex: Exception) {
            failOrRetry(action, now, ex)
        }
    }

    /**
     * Gagal transien vs menyerah: selama aksi belum melewati [RadiusProperties.maxRetry],
     * biarkan PENDING agar diulang (radius-db mungkin sesaat mati); setelah itu tandai FAILED
     * agar tak mengulang selamanya (poison). Sebab selalu direkam ke `detail`.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun failOrRetry(action: BngAction, now: Instant, ex: Exception) {
        if (Duration.between(action.requestedAt, now) > props.maxRetry) {
            action.fail(ex.message)
            log.warn("Provisioning {} (aksi {}) menyerah setelah {}: {}", action.username, action.id, props.maxRetry, ex.message)
        } else {
            action.retryLater(ex.message)
            log.info("Provisioning {} (aksi {}) gagal transien, akan diulang: {}", action.username, action.id, ex.message)
        }
        bngActionRepository.save(action)
    }

    private fun resolvePassword(action: BngAction): String =
        action.subscriberAccessId?.let { subscriberAccessRepository.findById(it)?.secret }?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("PROVISION ${action.username}: password akun tak terbaca")

    /** Reservasi Framed-IP-Address (DHCP/Static) dibaca live dari akun; null utk PPPoE/Hotspot. */
    private fun resolveFramedIp(action: BngAction): String? =
        action.subscriberAccessId?.let { subscriberAccessRepository.findById(it)?.framedIp }

    private fun requireGroup(action: BngAction): String =
        action.groupname?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("${action.action} ${action.username}: nama grup paket tak terbawa")

    private fun requireRateLimit(action: BngAction): String =
        action.rateLimit?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("SYNC_GROUP ${action.groupname}: rate-limit grup tak terbawa")

    /**
     * Identitas radius-db per skema autentikasi: PPPoE/Hotspot di-prefix kode tenant
     * (`"{slug}:{username}"`) — username login bisa kembar antar-tenant; DHCP/Static memakai
     * MAC apa adanya (global-unik) tanpa prefix. Dibaca dari `authType` yang dibawa aksi
     * agar DEPROVISION (lepas dari akun) tetap tahu skemanya.
     */
    private fun identity(slug: String, action: BngAction): String =
        if (action.authType.macBased) action.username else scoped(slug, action.username)

    /** Prefix kode tenant ke username — kunci SQL radius-db `"{slug}:{username}"` (S0). */
    private fun scoped(slug: String, username: String): String = "$slug:$username"
}

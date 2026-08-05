package com.duluin.ftth.monitoring.application.service

import com.duluin.ftth.bng.BngApi
import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.monitoring.AlarmsChangedEvent
import com.duluin.ftth.monitoring.application.port.outbound.AlarmRepository
import com.duluin.ftth.monitoring.domain.model.AlarmKind
import com.duluin.ftth.tenancy.TenantApi
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.ApplicationEventPublisher
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Mengangkat kondisi PPPoE-putus (dari `bng`) menjadi alarm `PPPOE_DOWN`, sehingga peta
 * bisa memerahkan pelanggan yang ONU-nya boleh jadi masih menyala tapi sesinya tak online.
 *
 * Kenapa lewat monitoring, bukan bng: pewarnaan peta memakai mesin alarm (satu alarm
 * terbuka per pelanggan, menutup sendiri saat pulih, muncul di daftar alarm & korelasi
 * insiden). `bng` cukup menyediakan FAKTA keadaan sesi lewat kontrak publiknya; monitoring
 * yang menerjemahkannya jadi alarm. Arah kebergantungan monitoring→bng searah dan sejalan
 * dengan monitoring→customer/network yang sudah ada.
 *
 * Kill-switch: `ftth.bng.pppoe-alarm-enabled`.
 */
@Component
class PppoeAlarmScheduler(
    private val tenantApi: TenantApi,
    private val evaluator: PppoeAlarmEvaluator,
    @Value("\${ftth.bng.pppoe-alarm-enabled:true}") private val enabled: Boolean,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Lebih jarang dari poll akunting (yang menyerap sesi tiap ~30 dtk) karena redaman
     * flap sudah ditangani ambang basi sesi di bng — 1 menit cukup untuk mewarnai peta
     * tanpa membebani korelasi insiden dengan publikasi tiap setengah menit.
     */
    @Scheduled(fixedDelayString = "\${ftth.bng.pppoe-alarm-interval:PT1M}")
    fun evaluateAll() {
        if (!enabled) return
        tenantApi.findActiveTenantIds().forEach { tenantId ->
            runCatching {
                TenantContext.runAs(tenantId) { evaluator.evaluateTenant(tenantId) }
            }.onFailure {
                log.warn("Penilaian alarm PPPoE tenant {} gagal: {}", tenantId, it.message)
            }
        }
    }
}

/**
 * Menilai alarm PPPoE satu tenant dalam transaksinya sendiri.
 *
 * Komponen terpisah dari [PppoeAlarmScheduler] — bukan method privat — karena
 * `@Transactional` Spring berlaku lewat proxy: memanggilnya dari kelas yang sama tak
 * akan pernah dibungkus transaksi. REQUIRES_NEW mengisolasi kegagalan satu tenant dari
 * yang lain di siklus yang sama, sekaligus jadi titik masuk yang bisa dipanggil langsung
 * oleh pengujian (dalam tenant context).
 */
@Component
class PppoeAlarmEvaluator(
    private val bngApi: BngApi,
    private val alarmEngine: AlarmEngine,
    private val alarmRepository: AlarmRepository,
    private val events: ApplicationEventPublisher,
) {
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun evaluateTenant(tenantId: UUID) {
        // Keadaan alarm SEBELUM menilai — dipakai memutuskan apakah perlu memicu korelasi
        // insiden. Publikasi hanya saat himpunan alarm PPPoE benar-benar berubah, bukan tiap
        // siklus, agar tenant yang steady-state tak memaksa korelasi ulang tiap menit.
        val openBefore = alarmRepository.findAllOpenByKind(AlarmKind.PPPOE_DOWN).mapTo(HashSet()) { it.entityId }

        // Satu pelanggan bisa punya beberapa akun (unit kedua): agregasi per pelanggan.
        // Pelanggan dinilai PUTUS bila punya akun aktif tapi TAK SATU pun online — kalau ada
        // satu line yang masih online, pelanggan belum benar-benar offline di peta.
        val byCustomer = bngApi.activeSubscriberLiveness().groupBy { it.customerId }
        byCustomer.forEach { (customerId, sessions) ->
            val down = sessions.none { it.online }
            // Label alarm memakai username akun yang putus (lebih bermakna bagi NOC yang
            // mengidentifikasi pelanggan lewat username PPPoE); untuk pelanggan yang pulih,
            // label diabaikan karena alarmnya ditutup.
            val label = sessions.firstOrNull { !it.online }?.username ?: sessions.first().username
            alarmEngine.evaluate(
                tenantId = tenantId,
                kind = AlarmKind.PPPOE_DOWN,
                entityId = customerId,
                entityLabel = label,
                conditionPresent = down,
                messageBuilder = { "Sesi PPPoE $label putus — pelanggan offline di BRAS" },
            )
        }

        // Swadaya-pulih: pelanggan yang alarmnya masih terbuka tapi kini TAK muncul di daftar
        // liveness (akun di-terminasi/isolir → tak lagi ACTIVE, atau baris sesinya lenyap) tak
        // akan pernah dinilai `conditionPresent=false` di atas — jadi ditutup eksplisit di sini,
        // supaya alarmnya tak menggantung selamanya.
        openBefore.filterNot { it in byCustomer }.forEach { alarmEngine.clearFor(AlarmKind.PPPOE_DOWN, it) }

        val openAfter = alarmRepository.findAllOpenByKind(AlarmKind.PPPOE_DOWN).mapTo(HashSet()) { it.entityId }
        if (openAfter != openBefore) events.publishEvent(AlarmsChangedEvent(tenantId))
    }
}

package com.duluin.ftth.simulator.radius

import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Menyalakan virtual-NAS (mesin sesi + responder DAE) saat app siap, mematikannya saat
 * shutdown. Digerbang `ftth.sim.radius.enabled`; bila radius-db tak dikonfigurasi (url
 * kosong) sim BRAS diam tanpa menggagalkan boot — mirror sikap provisioning app.
 *
 * Rekonsiliasi berjalan di [ScheduledExecutorService] sederhana (bukan @Scheduled Spring)
 * demi gaya ringkas yang sama dengan agen OLT — satu utas, di-cancel rapi saat berhenti.
 */
@Component
@ConditionalOnProperty(prefix = "ftth.sim.radius", name = ["enabled"], havingValue = "true", matchIfMissing = true)
class RadiusSimRunner(
    private val props: RadiusSimProperties,
    private val radiusDb: RadiusSimDataSource,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private var scheduler: ScheduledExecutorService? = null
    private var responder: DaeResponder? = null

    @EventListener(ApplicationReadyEvent::class)
    fun start() {
        val dataSource = radiusDb.dataSource
        if (dataSource == null) {
            log.info("Simulator BRAS/RADIUS dilewati — radius-db tak dikonfigurasi")
            return
        }
        val engine = VirtualNasEngine(dataSource, props)

        // Responder DAE: server menembak Disconnect/CoA ke sini saat isolir/Reset Login/CoA.
        responder = DaeResponder(props.daeBindAddress, props.daePort, props.daeSecret, engine).also { it.start() }

        // Mesin sesi: rekonsiliasi berkala (buat/tumbuhkan/tutup baris radacct).
        val tickMs = props.tickInterval.toMillis().coerceAtLeast(1_000)
        scheduler = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "sim-radius-engine").apply { isDaemon = true } }
            .also { exec ->
                exec.scheduleWithFixedDelay({
                    runCatching { engine.reconcile(Instant.now()) }
                        .onFailure { log.warn("Rekonsiliasi virtual-NAS gagal (radius-db mati?): {}", it.message) }
                }, 0, tickMs, TimeUnit.MILLISECONDS)
            }

        log.info(
            "Simulator BRAS/RADIUS aktif: DAE {}/{} (UDP), rekonsiliasi tiap {} dtk, dial-ulang {} dtk",
            props.daeBindAddress, props.daePort, tickMs / 1000, props.reconnectAfter.seconds,
        )
    }

    @PreDestroy
    fun stop() {
        scheduler?.shutdownNow()
        responder?.stop()
    }
}

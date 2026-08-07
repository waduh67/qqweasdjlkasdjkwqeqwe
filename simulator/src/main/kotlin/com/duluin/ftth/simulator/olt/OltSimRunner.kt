package com.duluin.ftth.simulator.olt

import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import java.time.Instant

/**
 * Menyalakan SEMUA agen OLT SNMP (satu per [OltInstance]) saat aplikasi siap, dan
 * mematikannya saat shutdown.
 *
 * Digerbang `ftth.sim.olt.enabled` (baku true) supaya peniru ini bisa dimatikan tanpa
 * mengubah kode — berguna saat lab hanya ingin menyalakan slice lain.
 */
@Component
@ConditionalOnProperty(prefix = "ftth.sim.olt", name = ["enabled"], havingValue = "true", matchIfMissing = true)
class OltSimRunner(private val props: OltSimProperties) {
    private val log = LoggerFactory.getLogger(javaClass)

    private data class RunningOlt(val inst: OltInstance, val agent: HsgqOltSnmpAgent, val onuCount: Int)

    private val olts: List<RunningOlt> = props.instances.map { inst ->
        val model = OltSimModel(inst.sysDescr, OltSimModel.populate(inst.ponCount, inst.onusPerPon, inst.macSlot))
        val agent = HsgqOltSnmpAgent(props.bindAddress, inst.port, inst.community) { model.snapshot(Instant.now()) }
        RunningOlt(inst, agent, model.onus.size)
    }

    @EventListener(ApplicationReadyEvent::class)
    fun start() {
        warnOnCollisions()
        olts.forEach { olt ->
            // Satu OLT gagal bind (mis. port bentrok) tak boleh menjatuhkan yang lain.
            runCatching { olt.agent.start() }
                .onSuccess {
                    log.info(
                        "OLT simulator HSGQ aktif: {} PON × {} ONU = {} ONU (community '{}', port {}, macSlot {})",
                        olt.inst.ponCount, olt.inst.onusPerPon, olt.onuCount,
                        olt.inst.community, olt.inst.port, olt.inst.macSlot,
                    )
                }
                .onFailure { log.error("Gagal menyalakan agen OLT di port {}: {}", olt.inst.port, it.message) }
        }
    }

    @PreDestroy
    fun stop() {
        olts.forEach { runCatching { it.agent.stop() } }
    }

    /** Peringatkan konfig yang membuat serial ONU kembar atau port bentrok antar-OLT. */
    private fun warnOnCollisions() {
        props.instances.groupBy { it.macSlot }.filterValues { it.size > 1 }.keys.forEach {
            log.warn("macSlot {} dipakai >1 OLT — serial ONU akan KEMBAR lintas-OLT; beri macSlot unik.", it)
        }
        props.instances.groupBy { it.port }.filterValues { it.size > 1 }.keys.forEach {
            log.warn("port {} dipakai >1 OLT — hanya satu agen yang bisa bind ke sana.", it)
        }
    }
}

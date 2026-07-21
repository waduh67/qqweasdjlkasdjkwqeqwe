package com.duluin.ftth.collector

import com.duluin.ftth.collector.adapter.AdapterRegistry
import com.duluin.ftth.collector.adapter.SimulatorOltAdapter
import com.duluin.ftth.collector.adapter.snmp.GponSnmpAdapter
import com.duluin.ftth.collector.adapter.snmp.MibProfiles
import org.slf4j.LoggerFactory

/**
 * Titik masuk agent.
 *
 * Konfigurasinya sengaja hanya tiga variabel lingkungan: alamat server, API key,
 * dan (opsional) apakah simulator diaktifkan. Sisanya — OLT mana yang di-polling,
 * seberapa sering — datang dari server, karena itulah yang diatur operator lewat
 * UI dan tidak boleh perlu masuk ke mesin collector untuk mengubahnya.
 */
private const val AGENT_VERSION = "0.2.0"

fun main() {
    val log = LoggerFactory.getLogger("ftth-collector")

    val serverUrl = env("FTTH_SERVER_URL", "http://localhost:8080")
    val apiKey = System.getenv("FTTH_COLLECTOR_KEY")
    if (apiKey.isNullOrBlank()) {
        log.error("FTTH_COLLECTOR_KEY belum diisi. Buat collector di UI untuk mendapatkan API key-nya.")
        return
    }

    // Simulator hanya ikut bila diminta eksplisit, supaya tidak pernah ada data
    // palsu yang diam-diam masuk ke lingkungan produksi. Saat aktif, ia
    // MENGGANTIKAN seluruh adapter SNMP — mencampur perangkat sungguhan dengan
    // perangkat tiruan dalam satu siklus hanya menghasilkan data yang tak jelas
    // asalnya.
    val simulatorEnabled = env("FTTH_COLLECTOR_SIMULATOR", "false").toBoolean()

    val adapters = if (simulatorEnabled) {
        MibProfiles.all().map { SimulatorOltAdapter(vendor = it.vendor) }
    } else {
        MibProfiles.all().map { GponSnmpAdapter(it) }
    }
    val registry = AdapterRegistry(adapters)

    log.info("ftth-collector {} → {}", AGENT_VERSION, serverUrl)
    if (simulatorEnabled) log.warn("MODE SIMULATOR aktif — data yang dikirim adalah tiruan, bukan dari perangkat")
    log.info("Vendor didukung: {}", registry.supportedVendors.joinToString())

    val agent = CollectorAgent(
        client = ServerClient(serverUrl, apiKey),
        registry = registry,
        agentVersion = AGENT_VERSION,
    )

    if (env("FTTH_COLLECTOR_ONCE", "false").toBoolean()) {
        val report = agent.runOnce()
        log.info("Selesai satu siklus: {}", report ?: "dijeda")
        return
    }

    Runtime.getRuntime().addShutdownHook(
        Thread {
            log.info("Menghentikan collector…")
            agent.stop()
        },
    )

    agent.run()
}

private fun env(name: String, default: String): String = System.getenv(name)?.takeIf { it.isNotBlank() } ?: default

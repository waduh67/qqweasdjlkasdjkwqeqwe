package com.duluin.ftth.collector

import com.duluin.ftth.collector.adapter.BngAdapterRegistry
import com.duluin.ftth.collector.adapter.MikrotikRouterOsAdapter
import com.duluin.ftth.collector.adapter.SimulatorBngAdapter
import com.duluin.ftth.collector.adapter.SimulatorOltAdapter
import com.duluin.ftth.snmp.AdapterRegistry
import com.duluin.ftth.snmp.GponSnmpAdapter
import com.duluin.ftth.snmp.HsgqEponSnmpAdapter
import com.duluin.ftth.snmp.MibProfiles
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

    // GPON (data-driven MibProfile) + EPON HSGQ (adapter tersendiri karena identitas MAC
    // & join dua-tabel — lihat HsgqEponSnmpAdapter). Simulator memerankan tiap vendor.
    val adapters = if (simulatorEnabled) {
        MibProfiles.all().map { SimulatorOltAdapter(vendor = it.vendor) } +
            SimulatorOltAdapter(vendor = HsgqEponSnmpAdapter.VENDOR)
    } else {
        MibProfiles.all().map { GponSnmpAdapter(it) } + HsgqEponSnmpAdapter()
    }
    val registry = AdapterRegistry(adapters)

    // Jalur BNG (sesi PPPoE): di mode simulator, satu SimulatorBngAdapter memerankan
    // BRAS vendor apa pun lewat fallback. Di mode nyata dipasang adapter sungguhan per
    // vendor; NAS bervendor lain hanya dicatat "belum didukung", bukan ditebak. Data-plane
    // RADIUS (provision/baca radacct/DAE) kini dipegang server pusat, jadi collector hanya
    // perlu adapter MIKROTIK-native (REST) untuk kontrol sesi on-prem.
    val bngRegistry = if (simulatorEnabled) {
        BngAdapterRegistry(emptyList(), fallback = SimulatorBngAdapter())
    } else {
        BngAdapterRegistry(listOf(MikrotikRouterOsAdapter()))
    }

    log.info("ftth-collector {} → {}", AGENT_VERSION, serverUrl)
    if (simulatorEnabled) log.warn("MODE SIMULATOR aktif — data yang dikirim adalah tiruan, bukan dari perangkat")
    log.info("Vendor OLT didukung: {}", registry.supportedVendors.joinToString())
    log.info("Vendor BRAS didukung: {}", bngRegistry.supportedVendors.joinToString().ifBlank { "(fallback simulator)" })

    val agent = CollectorAgent(
        client = HttpServerClient(serverUrl, apiKey),
        registry = registry,
        agentVersion = AGENT_VERSION,
        bngRegistry = bngRegistry,
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

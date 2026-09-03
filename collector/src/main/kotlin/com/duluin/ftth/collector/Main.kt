package com.duluin.ftth.collector

import com.duluin.ftth.collector.adapter.BngAdapterRegistry
import com.duluin.ftth.collector.adapter.MikrotikRouterOsAdapter
import com.duluin.ftth.collector.adapter.ProvisioningAdapterRegistry
import com.duluin.ftth.collector.adapter.OltProvisioningAdapterRegistry
import com.duluin.ftth.collector.adapter.FileRouterOsProvisioningStateStore
import com.duluin.ftth.collector.adapter.RouterOsProvisioningAdapter
import com.duluin.ftth.collector.adapter.SimulatorBngAdapter
import com.duluin.ftth.collector.adapter.SimulatorOltAdapter
import com.duluin.ftth.collector.adapter.hsgq.ProvisionalHsgqProvisioningAdapter
import com.duluin.ftth.snmp.AdapterRegistry
import com.duluin.ftth.snmp.GponSnmpAdapter
import com.duluin.ftth.snmp.HsgqEponSnmpAdapter
import com.duluin.ftth.snmp.MibProfiles
import org.slf4j.LoggerFactory
import java.nio.file.Path

/**
 * Titik masuk agent.
 *
 * Konfigurasinya memakai alamat server, API key, mode simulator, dan direktori state
 * provisioning lokal. Sisanya — OLT mana yang di-polling,
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
    val simulatorEnabled = CollectorRuntimeMode.resolve(
        environment = env("FTTH_ENVIRONMENT", "development"),
        simulatorRequested = env("FTTH_COLLECTOR_SIMULATOR", "false").toBoolean(),
    ).simulatorEnabled

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
    val provisioningStateDirectory = Path.of(
        env(
            "FTTH_COLLECTOR_STATE_DIR",
            Path.of(System.getProperty("user.home"), ".local", "state", "ftth-collector").toString(),
        ),
    )
    val provisioningRegistry = ProvisioningAdapterRegistry(
        if (simulatorEnabled) {
            emptyList()
        } else {
            listOf(
                RouterOsProvisioningAdapter(
                    stateStore = FileRouterOsProvisioningStateStore(
                        provisioningStateDirectory.resolve("routeros-provisioning-state.json"),
                    ),
                ),
            )
        },
    )
    val oltProvisioningRegistry = OltProvisioningAdapterRegistry(
        listOf(ProvisionalHsgqProvisioningAdapter()),
    )

    log.info("ftth-collector {} → {}", AGENT_VERSION, serverUrl)
    if (simulatorEnabled) log.warn("MODE SIMULATOR aktif — data yang dikirim adalah tiruan, bukan dari perangkat")
    log.info("Vendor OLT didukung: {}", registry.supportedVendors.joinToString())
    log.info("Vendor BRAS didukung: {}", bngRegistry.supportedVendors.joinToString().ifBlank { "(fallback simulator)" })

    val agent = CollectorAgent(
        client = HttpServerClient(serverUrl, apiKey),
        registry = registry,
        agentVersion = AGENT_VERSION,
        bngRegistry = bngRegistry,
        provisioningRegistry = provisioningRegistry,
        oltProvisioningRegistry = oltProvisioningRegistry,
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

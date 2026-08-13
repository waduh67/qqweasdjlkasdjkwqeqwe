package com.duluin.ftth.cpe.application.service

import com.duluin.ftth.bng.BngApi
import com.duluin.ftth.common.security.CurrentUserProvider
import com.duluin.ftth.cpe.application.port.inbound.AcsActivityView
import com.duluin.ftth.cpe.application.port.inbound.AcsBulkRefreshView
import com.duluin.ftth.cpe.application.port.inbound.AcsConsoleQuery
import com.duluin.ftth.cpe.application.port.inbound.AcsDeviceFilter
import com.duluin.ftth.cpe.application.port.inbound.AcsDeviceRowView
import com.duluin.ftth.cpe.application.port.inbound.AcsHealthView
import com.duluin.ftth.cpe.application.port.inbound.AcsServerInfoView
import com.duluin.ftth.cpe.application.port.inbound.AcsSignalFilter
import com.duluin.ftth.cpe.application.port.inbound.AcsStatsView
import com.duluin.ftth.cpe.application.port.inbound.AcsStatusFilter
import com.duluin.ftth.cpe.application.port.inbound.RefreshAcsFleetUseCase
import com.duluin.ftth.cpe.application.port.outbound.AcsGateway
import com.duluin.ftth.cpe.application.port.outbound.CpeActionLogRepository
import com.duluin.ftth.cpe.application.port.outbound.CpeDeviceRepository
import com.duluin.ftth.cpe.config.OntAcsProperties
import com.duluin.ftth.customer.CustomerApi
import com.duluin.ftth.monitoring.MonitoringApi
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/**
 * Konsol ACS se-armada: ringkasan, tabel device, jejak aktivitas, kesehatan server,
 * dan sapuan "Segarkan Batch".
 *
 * Berdiri sendiri di samping [CpeService] karena penontonnya berbeda. [CpeService]
 * melayani panel satu pelanggan dan hanya butuh repo sendiri + ACS; yang di sini
 * merangkai empat sumber (cpe, monitoring, bng, customer) supaya satu baris tabel
 * memuat SSID, PPPoE, dan RX sekaligus. Menggabungkannya berarti setiap panel
 * pelanggan ikut menyeret tiga dependensi lintas-module yang tak dipakainya.
 *
 * Pemuatan satu halaman = EMPAT query, tak peduli armadanya 10 atau 3.000 perangkat:
 * proyeksi CPE → metrik optik per-ONU → PPPoE per-pelanggan → nama pelanggan.
 */
@Service
class AcsConsoleService(
    private val deviceRepository: CpeDeviceRepository,
    private val actionLogRepository: CpeActionLogRepository,
    private val acsGateway: AcsGateway,
    private val monitoringApi: MonitoringApi,
    private val bngApi: BngApi,
    private val customerApi: CustomerApi,
    private val syncScheduler: CpeSyncScheduler,
    private val bulkRefreshRunner: AcsBulkRefreshRunner,
    private val currentUser: CurrentUserProvider,
    private val ont: OntAcsProperties,
    // Alamat NBI dibaca sebagai properti polos, BUKAN dengan menyuntik
    // `GenieAcsProperties`: kelas itu milik lapisan adapter, dan lapisan application
    // tak boleh bergantung padanya (arah panahnya justru sebaliknya).
    @Value("\${ftth.cpe.genieacs.base-url:}") private val nbiBaseUrl: String,
    @Value("\${ftth.cpe.online-stale-after:PT15M}") private val onlineStaleAfter: Duration,
    @Value("\${ftth.cpe.sync-interval:PT5M}") private val syncInterval: Duration,
    @Value("\${ftth.cpe.bulk-refresh-max:50}") private val bulkRefreshMax: Int,
    @Value("\${ftth.cpe.bulk-refresh-budget:PT20S}") private val bulkRefreshBudget: Duration,
    // Selang memoisasi probe. Properti (bukan konstanta) semata agar uji integrasi bisa
    // mematikannya: dengan memoisasi hidup, satu tes yang mematikan ACS akan menerima
    // jawaban "ONLINE" yang ditinggalkan tes sebelumnya di context yang sama.
    @Value("\${ftth.cpe.health-probe-ttl:PT10S}") private val probeTtl: Duration,
) : AcsConsoleQuery, RefreshAcsFleetUseCase {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Hasil probe terakhir beserta waktunya. Teknisi memegang izin ini dan halaman
     * menyegarkan dirinya sendiri, jadi tanpa memoisasi satu ruang kerja berisi sepuluh
     * tab akan menghujani NBI dengan probe yang jawabannya sudah pasti sama.
     */
    private val cachedProbe = AtomicReference<Pair<Instant, AcsHealthView>?>(null)

    override fun serverInfo(): AcsServerInfoView {
        val host = ont.publicHost.trim()
        return AcsServerInfoView(
            nbiBaseUrl = nbiBaseUrl,
            cwmpUrl = host.takeIf { it.isNotEmpty() }?.let { "http://$it:${ont.cwmpPort}" },
            acsUsername = ont.acsUsername.takeIf { it.isNotEmpty() },
            acsPassword = ont.acsPassword.takeIf { it.isNotEmpty() },
            connectionRequestUsername = ont.connectionRequestUsername.takeIf { it.isNotEmpty() },
            connectionRequestPassword = ont.connectionRequestPassword.takeIf { it.isNotEmpty() },
            periodicInformEnabled = true,
            periodicInformIntervalSeconds = ont.periodicInformInterval.seconds,
            syncIntervalSeconds = syncInterval.seconds,
            configured = host.isNotEmpty(),
        )
    }

    override fun health(): AcsHealthView {
        val now = Instant.now()
        cachedProbe.get()?.let { (at, view) ->
            if (Duration.between(at, now) < probeTtl) return view
        }
        val probe = acsGateway.probe()
        // Pesan asli exception memuat URI NBI lengkap — ia berhenti di log server.
        if (!probe.reachable) log.warn("Probe kesehatan ACS gagal: {}", probe.error)
        val view = AcsHealthView(
            status = if (probe.reachable) "ONLINE" else "OFFLINE",
            latencyMs = probe.latencyMs,
            checkedAt = now,
            message = if (probe.reachable) {
                "Server ACS terjangkau"
            } else {
                "Server ACS tak terjangkau" + (probe.error?.let { " ($it)" } ?: "")
            },
        )
        cachedProbe.set(now to view)
        return view
    }

    @Transactional(readOnly = true)
    override fun stats(filter: AcsDeviceFilter): AcsStatsView {
        val rows = rows(filter)
        val samples = rows.mapNotNull { it.rxPowerDbm }
        return AcsStatsView(
            totalDevices = rows.size,
            onlineDevices = rows.count { it.online },
            offlineDevices = rows.count { !it.online },
            // Rata-rata RX SAJA. TX besaran lain (daya pancar ONU); mencampurnya
            // menghasilkan angka yang tak berarti apa pun.
            avgRxPowerDbm = samples.takeIf { it.isNotEmpty() }
                ?.let { round1(it.sum() / it.size) },
            signalSampleCount = samples.size,
            lastSyncAt = syncScheduler.lastRunAt(),
            lastSyncOk = syncScheduler.lastRunOk(),
        )
    }

    @Transactional(readOnly = true)
    override fun devices(filter: AcsDeviceFilter): List<AcsDeviceRowView> = rows(filter)

    @Transactional(readOnly = true)
    override fun activity(limit: Int, deviceId: UUID?): List<AcsActivityView> {
        val capped = limit.coerceIn(1, MAX_ACTIVITY)
        val logs = if (deviceId != null) {
            actionLogRepository.findByDeviceId(deviceId).take(capped)
        } else {
            actionLogRepository.findRecentForCurrentTenant(capped)
        }
        if (logs.isEmpty()) return emptyList()

        // Serial & nama pelanggan diambil sekali untuk seluruh halaman log — bukan
        // satu query per baris.
        val devices = deviceRepository.findByIds(logs.map { it.deviceId }.toSet()).associateBy { it.id }
        val names = customerNames(devices.values.mapNotNull { it.customerId }.toSet())
        return logs.map { entry ->
            val device = devices[entry.deviceId]
            AcsActivityView(
                id = entry.id,
                deviceId = entry.deviceId,
                serialNumber = device?.serialNumber,
                customerName = device?.customerId?.let { names[it] },
                action = entry.action.name,
                status = entry.status.name,
                detail = entry.detail,
                requestedByEmail = entry.requestedByEmail,
                requestedAt = entry.requestedAt,
            )
        }
    }

    /**
     * Sapuan connection request — SINKRON, BERPLAFON, BERANGGARAN WAKTU.
     *
     * Tak ada infrastruktur async di aplikasi ini; membuatnya berarti memindahkan
     * `TenantContext` (ThreadLocal) dan identitas aktor ke thread pekerja secara manual
     * plus tabel status job — satu subsistem demi satu tombol. Maka permintaannya
     * ditahan, dan dua rem inilah yang menjaganya tetap waras:
     *
     * - PLAFON ([bulkRefreshMax], bawaan 50) — armada 3.000 ONT tak dihabiskan sekali klik.
     * - ANGGARAN WAKTU ([bulkRefreshBudget], bawaan 20 detik) — read timeout NBI 15 detik;
     *   50 permintaan yang mati berurutan = 12,5 menit menahan satu thread servlet dan
     *   satu koneksi dari pool Hikari yang isinya sepuluh.
     *
     * Hanya perangkat ONLINE yang disentuh: yang offline dijamin menjawab "Not Connect",
     * jadi memanggilnya cuma membakar anggaran. Yang paling lama tak inform didahulukan —
     * itu yang paling perlu dibangunkan.
     *
     * Sengaja TANPA `@Transactional`: tiap perangkat ditangani [AcsBulkRefreshRunner]
     * dalam transaksinya sendiri. Membungkus seluruh sapuan dalam satu transaksi berarti
     * menahan satu koneksi database selama seluruh anggaran waktu padahal 99% waktunya
     * habis menunggu jaringan.
     */
    override fun refreshAll(): AcsBulkRefreshView {
        val now = Instant.now()
        val candidates = deviceRepository.findAllForCurrentTenant()
            .filter { it.isOnline(now, onlineStaleAfter) }
            .sortedBy { it.lastInformAt }
        val targets = candidates.take(bulkRefreshMax.coerceAtLeast(1))

        val actor = currentUser.current()
        val deadline = now.plus(bulkRefreshBudget)
        var connected = 0
        var queued = 0
        var failed = 0
        var attempted = 0
        for (device in targets) {
            if (Instant.now().isAfter(deadline)) {
                log.info("Segarkan batch berhenti di {}/{} perangkat: anggaran waktu habis", attempted, targets.size)
                break
            }
            attempted++
            when (bulkRefreshRunner.refreshOne(device.id, device.genieacsId, actor.userId, actor.email)) {
                RefreshOutcome.CONNECTED -> connected++
                RefreshOutcome.QUEUED -> queued++
                RefreshOutcome.FAILED -> failed++
            }
        }

        val skipped = candidates.size - attempted
        val message = buildString {
            append("$attempted dari ${candidates.size} perangkat online disegarkan")
            append(" — $connected terhubung, $queued diantre")
            if (failed > 0) append(", $failed gagal")
            if (skipped > 0) append(". $skipped sisanya belum tersentuh (plafon/anggaran waktu); klik lagi untuk melanjutkan")
        }
        return AcsBulkRefreshView(
            candidates = candidates.size,
            attempted = attempted,
            connected = connected,
            queued = queued,
            failed = failed,
            skipped = skipped,
            message = message,
        )
    }

    /**
     * Merakit seluruh baris tabel lalu menyaringnya DI KOTLIN.
     *
     * Penyaringan tak bisa turun ke SQL: SSID ada di `cpe_device`, PPPoE di module bng,
     * dan RX di module monitoring — tak ada satu `WHERE` pun yang bisa melihat ketiganya.
     */
    private fun rows(filter: AcsDeviceFilter): List<AcsDeviceRowView> {
        val devices = deviceRepository.findAllForCurrentTenant()
        if (devices.isEmpty()) return emptyList()
        val now = Instant.now()

        val metrics = monitoringApi.latestMetricsByOnuIds(devices.mapNotNull { it.onuId }.toSet())
        val customerIds = devices.mapNotNull { it.customerId }.toSet()
        val pppoe = bngApi.findPppoeByCustomerIds(customerIds)
        val names = customerNames(customerIds)

        val q = filter.q?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() }
        val brand = filter.brand?.trim()?.takeIf { it.isNotEmpty() }

        return devices.map { device ->
            val metric = device.onuId?.let { metrics[it] }
            val session = device.customerId?.let { pppoe[it] }
            AcsDeviceRowView(
                id = device.id,
                serialNumber = device.serialNumber,
                customerId = device.customerId,
                customerName = device.customerId?.let { names[it] },
                manufacturer = device.manufacturer,
                model = device.model,
                softwareVersion = device.softwareVersion,
                online = device.isOnline(now, onlineStaleAfter),
                lastInformAt = device.lastInformAt,
                ipAddress = device.ipAddress,
                ssid = device.ssid,
                pppoeUsername = session?.username,
                pppoeOnline = session?.online,
                rxPowerDbm = metric?.rxPowerDbm,
                txPowerDbm = metric?.txPowerDbm,
                temperatureC = device.temperatureC,
            )
        }.filter { row ->
            matchesQuery(row, q) &&
                matchesStatus(row, filter.status) &&
                matchesSignal(row, filter.signal) &&
                (brand == null || row.manufacturer.equals(brand, ignoreCase = true))
        }
    }

    private fun customerNames(ids: Set<UUID>): Map<UUID, String> =
        if (ids.isEmpty()) emptyMap() else customerApi.findCustomersByIds(ids).associate { it.id to it.name }

    private fun matchesQuery(row: AcsDeviceRowView, q: String?): Boolean {
        if (q == null) return true
        return listOfNotNull(row.serialNumber, row.ssid, row.pppoeUsername, row.customerName)
            .any { it.lowercase(Locale.ROOT).contains(q) }
    }

    private fun matchesStatus(row: AcsDeviceRowView, status: AcsStatusFilter): Boolean = when (status) {
        AcsStatusFilter.ALL -> true
        AcsStatusFilter.ONLINE -> row.online
        AcsStatusFilter.OFFLINE -> !row.online
    }

    private fun matchesSignal(row: AcsDeviceRowView, signal: AcsSignalFilter): Boolean {
        if (signal == AcsSignalFilter.ALL) return true
        val rx = row.rxPowerDbm ?: return signal == AcsSignalFilter.UNKNOWN
        return when (signal) {
            AcsSignalFilter.GOOD -> rx >= WARNING_DBM
            AcsSignalFilter.WARN -> rx < WARNING_DBM && rx >= CRITICAL_DBM
            AcsSignalFilter.CRITICAL -> rx < CRITICAL_DBM
            else -> false
        }
    }

    /** dBm dibulatkan satu desimal; presisi lebih dari itu hanya derau pembacaan SNMP. */
    private fun round1(value: Double): Double = Math.round(value * 10.0) / 10.0

    companion object {
        /** Plafon keras baris log; jendela "View Logs" bukan pengganti jejak audit penuh. */
        const val MAX_ACTIVITY = 500

        /**
         * Ambang RX yang SAMA dengan alarm `ONU_LOW_RX` di module monitoring dan penanda
         * warna di layar optik. Disalin sebagai konstanta, bukan diimpor: nilainya milik
         * domain monitoring/customer dan module cpe tak boleh menembus batas module untuk
         * membaca kelas domain orang lain. Kalau ambangnya berubah di sana, ubah juga di sini.
         */
        private const val WARNING_DBM = -25.0
        private const val CRITICAL_DBM = -27.0
    }
}

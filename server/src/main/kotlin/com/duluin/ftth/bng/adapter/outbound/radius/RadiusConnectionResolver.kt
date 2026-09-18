package com.duluin.ftth.bng.adapter.outbound.radius

import com.duluin.ftth.bng.application.port.outbound.RadiusServerRepository
import com.duluin.ftth.bng.application.service.RadiusServerAllocationService
import com.duluin.ftth.bng.config.RadiusProperties
import com.duluin.ftth.bng.domain.model.RadiusServer
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.DisposableBean
import org.springframework.stereotype.Component
import java.sql.Connection
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Sumber koneksi ke radius-db platform dengan dukungan multi-cluster sharding.
 *
 * Tiap tenant dialokasikan ke salah satu node FreeRADIUS ([RadiusServer]) berdasarkan kuota
 * kapasitas ([RadiusServer.maxTenants]). Koneksi JDBC ke database radius-db pada node
 * tersebut di-cache dalam [serverPools] secara on-demand.
 *
 * Bila belum ada node multi-server yang terdaftar, sistem fallback ke pool lokal tunggal
 * dari [RadiusProperties].
 */
@Component
class RadiusConnectionResolver(
    private val props: RadiusProperties,
    private val allocationService: RadiusServerAllocationService? = null,
    private val serverRepository: RadiusServerRepository? = null,
) : DisposableBean {

    private val log = LoggerFactory.getLogger(javaClass)

    private val dataSource: HikariDataSource? = buildPool(props)
    private val serverPools = ConcurrentHashMap<UUID, HikariDataSource>()

    /** True bila radius-db dikonfigurasi — baik pool lokal maupun node multi-server aktif. */
    val configured: Boolean
        get() = dataSource != null || (serverRepository?.countActive() ?: 0L) > 0L

    /**
     * Mengembalikan koneksi JDBC untuk provisioning & akunting tenant [tenantId].
     */
    fun connectionFor(tenantId: UUID): Connection {
        val server = allocationService?.getOrAllocateServerForTenant(tenantId)
        if (server != null) {
            val pool = serverPools.computeIfAbsent(server.id) { buildServerPool(server) }
            return pool.connection
        }
        return (dataSource ?: error("radius-db belum dikonfigurasi (tidak ada server RADIUS aktif dan ftth.radius.url kosong)")).connection
    }

    /**
     * Membersihkan dan menutup pool koneksi saat konfigurasi node diperbarui atau dihapus.
     */
    fun evictPool(serverId: UUID) {
        serverPools.remove(serverId)?.let { pool ->
            runCatching { pool.close() }
        }
    }

    private fun buildPool(props: RadiusProperties): HikariDataSource? {
        if (!props.enabled || props.url.isBlank()) {
            log.info("radius-db lokal tak dikonfigurasi — menunggu node RADIUS platform")
            return null
        }
        val config = HikariConfig().apply {
            jdbcUrl = props.url
            username = props.username
            password = props.password
            maximumPoolSize = props.maxPoolSize
            poolName = "radius-db-default"
            // Jangan tahan boot kalau radius-db sesaat tak sehat — sambungkan saat dipakai.
            initializationFailTimeout = -1
        }
        return HikariDataSource(config)
    }

    private fun buildServerPool(server: RadiusServer): HikariDataSource {
        log.info("Membangun pool koneksi radius-db untuk node '{}' ({})", server.name, server.host)
        val config = HikariConfig().apply {
            jdbcUrl = server.dbUrl
            username = server.dbUser
            password = server.dbPassword
            maximumPoolSize = props.maxPoolSize
            poolName = "radius-node-${server.id}"
            initializationFailTimeout = -1
        }
        return HikariDataSource(config)
    }

    override fun destroy() {
        dataSource?.close()
        serverPools.values.forEach { pool ->
            runCatching { pool.close() }
        }
        serverPools.clear()
    }
}

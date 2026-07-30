package com.duluin.ftth.bng.adapter.outbound.radius

import com.duluin.ftth.bng.config.RadiusProperties
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.DisposableBean
import org.springframework.stereotype.Component
import java.sql.Connection
import java.util.UUID

/**
 * Sumber koneksi ke radius-db platform — sekaligus JAHITAN sharding masa depan.
 *
 * Hari ini balikin SATU cluster untuk semua tenant ("1 FreeRADIUS logis, stateless":
 * beban RADIUS = rate paket, bukan jumlah user; 1 daemon sanggup puluhan-ribu user, dan
 * data sudah tenant-keyed lewat `sql_user_name`/`nas.shortname`). Bila kelak perlu shard
 * per-tenant di skala ekstrem, cukup ubah [connectionFor] memilih pool berdasarkan
 * [tenantId] — pemanggil (adapter provisioning) tak berubah.
 *
 * Pool dibangun HANYA bila [RadiusProperties.url] terisi & [RadiusProperties.enabled];
 * kalau tidak [configured] = false dan aplikasi tetap boot tanpa datasource kedua. Init
 * pool dibuat toleran (`initializationFailTimeout = -1`) agar radius-db yang sesaat mati
 * saat boot tidak menggagalkan start server — koneksi baru dicoba saat benar-benar dipakai.
 */
@Component
class RadiusConnectionResolver(props: RadiusProperties) : DisposableBean {

    private val log = LoggerFactory.getLogger(javaClass)

    private val dataSource: HikariDataSource? = buildPool(props)

    /** True bila radius-db dikonfigurasi — provisioning server-side aktif. */
    val configured: Boolean get() = dataSource != null

    /**
     * Koneksi untuk provisioning tenant [tenantId]. [tenantId] kini diabaikan (satu
     * cluster) — ia ADA di tanda tangan sebagai jahitan sharding, bukan basa-basi.
     */
    fun connectionFor(@Suppress("UNUSED_PARAMETER") tenantId: UUID): Connection =
        (dataSource ?: error("radius-db belum dikonfigurasi (ftth.radius.url kosong)")).connection

    private fun buildPool(props: RadiusProperties): HikariDataSource? {
        if (!props.enabled || props.url.isBlank()) {
            log.info("radius-db tak dikonfigurasi — provisioning RADIUS server-side nonaktif")
            return null
        }
        val config = HikariConfig().apply {
            jdbcUrl = props.url
            username = props.username
            password = props.password
            maximumPoolSize = props.maxPoolSize
            poolName = "radius-db"
            // Jangan tahan boot kalau radius-db sesaat tak sehat — sambungkan saat dipakai.
            initializationFailTimeout = -1
        }
        return HikariDataSource(config)
    }

    override fun destroy() {
        dataSource?.close()
    }
}

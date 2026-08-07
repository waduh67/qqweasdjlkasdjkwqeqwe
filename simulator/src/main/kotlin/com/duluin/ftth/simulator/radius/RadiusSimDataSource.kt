package com.duluin.ftth.simulator.radius

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.DisposableBean
import org.springframework.stereotype.Component
import javax.sql.DataSource

/**
 * Pool koneksi ke radius-db untuk virtual-NAS. Cermin ramping dari
 * [com.duluin.ftth.bng.adapter.outbound.radius.RadiusConnectionResolver]: dibangun hanya
 * bila `ftth.sim.radius.url` terisi & `enabled`; kalau tidak [configured] = false dan sim
 * BRAS diam. `initializationFailTimeout = -1` agar radius-db yang telat siap tak menggagalkan
 * boot — koneksi dicoba saat benar-benar dipakai.
 */
@Component
class RadiusSimDataSource(props: RadiusSimProperties) : DisposableBean {

    private val log = LoggerFactory.getLogger(javaClass)

    private val hikari: HikariDataSource? = build(props)

    val dataSource: DataSource? get() = hikari
    val configured: Boolean get() = hikari != null

    private fun build(props: RadiusSimProperties): HikariDataSource? {
        if (!props.enabled || props.url.isBlank()) {
            log.info("radius-db tak dikonfigurasi — simulator BRAS/RADIUS nonaktif")
            return null
        }
        val config = HikariConfig().apply {
            jdbcUrl = props.url
            username = props.username
            password = props.password
            maximumPoolSize = 3
            poolName = "sim-radius-db"
            initializationFailTimeout = -1
        }
        return HikariDataSource(config)
    }

    override fun destroy() {
        hikari?.close()
    }
}

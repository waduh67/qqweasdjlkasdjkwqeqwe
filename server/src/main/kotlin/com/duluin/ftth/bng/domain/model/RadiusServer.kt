package com.duluin.ftth.bng.domain.model

import com.duluin.ftth.common.domain.UuidV7
import com.duluin.ftth.common.domain.error.ValidationException
import java.util.UUID

/** Status node/cluster RADIUS: ACTIVE melayani tenant baru, DRAINING tidak menerima tenant baru, DISABLED nonaktif. */
enum class RadiusServerStatus { ACTIVE, DRAINING, DISABLED }

/**
 * Node/server FreeRADIUS yang dijalankan PLATFORM (operator SaaS) di VPS atau bare-metal
 * terpisah ber-IP publik statis. Sistem mendukung pendaftaran multi-server dengan batas
 * kapasitas per server ([maxTenants]), sehingga tenant dialokasikan otomatis (*auto-distribute*)
 * tanpa membebani satu node secara berlebih.
 */
class RadiusServer private constructor(
    val id: UUID,
    name: String,
    host: String,
    authPort: Int,
    acctPort: Int,
    coaPort: Int,
    sharedSecret: String,
    dbUrl: String,
    dbUser: String,
    dbPassword: String,
    maxTenants: Int,
    status: RadiusServerStatus,
) {
    var name: String = name
        private set

    /** IP publik atau FQDN VPS tempat FreeRADIUS melayani autentikasi/akunting. */
    var host: String = host
        private set

    /** Port autentikasi RADIUS (RFC 2865), default 1812. */
    var authPort: Int = authPort
        private set

    /** Port akunting RADIUS (RFC 2866), default 1813. */
    var acctPort: Int = acctPort
        private set

    /** Port DAE / CoA balik ke router (RFC 5176), default 3799. */
    var coaPort: Int = coaPort
        private set

    /** Shared secret default untuk router/NAS yang diarahkan ke node ini. */
    var sharedSecret: String = sharedSecret
        private set

    /** JDBC URL menuju radius-db pada node ini (mis. jdbc:postgresql://ip:5432/radius). */
    var dbUrl: String = dbUrl
        private set

    var dbUser: String = dbUser
        private set

    /** Password database radius-db (disimpan terenkripsi di persistence). */
    var dbPassword: String = dbPassword
        private set

    /** Batas jumlah tenant maksimum yang boleh ditampung oleh node ini. */
    var maxTenants: Int = maxTenants
        private set

    var status: RadiusServerStatus = status
        private set

    fun update(
        name: String,
        host: String,
        authPort: Int,
        acctPort: Int,
        coaPort: Int,
        sharedSecret: String,
        dbUrl: String,
        dbUser: String,
        dbPassword: String?,
        maxTenants: Int,
        status: RadiusServerStatus,
    ) {
        validate(name, host, authPort, acctPort, coaPort, sharedSecret, dbUrl, dbUser, maxTenants)
        this.name = name.trim()
        this.host = host.trim()
        this.authPort = authPort
        this.acctPort = acctPort
        this.coaPort = coaPort
        this.sharedSecret = sharedSecret.trim()
        this.dbUrl = dbUrl.trim()
        this.dbUser = dbUser.trim()
        if (!dbPassword.isNullOrBlank()) {
            this.dbPassword = dbPassword.trim()
        }
        this.maxTenants = maxTenants
        this.status = status
    }

    companion object {
        fun create(
            name: String,
            host: String,
            authPort: Int = 1812,
            acctPort: Int = 1813,
            coaPort: Int = 3799,
            sharedSecret: String,
            dbUrl: String,
            dbUser: String,
            dbPassword: String,
            maxTenants: Int = 2,
            status: RadiusServerStatus = RadiusServerStatus.ACTIVE,
            id: UUID = UuidV7.generate(),
        ): RadiusServer {
            validate(name, host, authPort, acctPort, coaPort, sharedSecret, dbUrl, dbUser, maxTenants)
            if (dbPassword.isBlank()) throw ValidationException("Password database radius-db wajib diisi")
            return RadiusServer(
                id = id,
                name = name.trim(),
                host = host.trim(),
                authPort = authPort,
                acctPort = acctPort,
                coaPort = coaPort,
                sharedSecret = sharedSecret.trim(),
                dbUrl = dbUrl.trim(),
                dbUser = dbUser.trim(),
                dbPassword = dbPassword.trim(),
                maxTenants = maxTenants,
                status = status,
            )
        }

        fun reconstitute(
            id: UUID,
            name: String,
            host: String,
            authPort: Int,
            acctPort: Int,
            coaPort: Int,
            sharedSecret: String,
            dbUrl: String,
            dbUser: String,
            dbPassword: String,
            maxTenants: Int,
            status: RadiusServerStatus,
        ): RadiusServer = RadiusServer(
            id = id,
            name = name,
            host = host,
            authPort = authPort,
            acctPort = acctPort,
            coaPort = coaPort,
            sharedSecret = sharedSecret,
            dbUrl = dbUrl,
            dbUser = dbUser,
            dbPassword = dbPassword,
            maxTenants = maxTenants,
            status = status,
        )

        private fun validate(
            name: String,
            host: String,
            authPort: Int,
            acctPort: Int,
            coaPort: Int,
            sharedSecret: String,
            dbUrl: String,
            dbUser: String,
            maxTenants: Int,
        ) {
            if (name.isBlank()) throw ValidationException("Nama server RADIUS wajib diisi")
            if (host.isBlank()) throw ValidationException("Host/IP server RADIUS wajib diisi")
            if (authPort !in 1..65535) throw ValidationException("Port autentikasi harus 1-65535")
            if (acctPort !in 1..65535) throw ValidationException("Port akunting harus 1-65535")
            if (coaPort !in 1..65535) throw ValidationException("Port CoA harus 1-65535")
            if (sharedSecret.isBlank()) throw ValidationException("Shared secret RADIUS wajib diisi")
            if (dbUrl.isBlank()) throw ValidationException("JDBC URL database radius-db wajib diisi")
            if (dbUser.isBlank()) throw ValidationException("User database radius-db wajib diisi")
            if (maxTenants < 1) throw ValidationException("Kapasitas maksimum tenant minimal 1")
        }
    }
}

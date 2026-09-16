package com.duluin.ftth.monitoring

import com.duluin.ftth.MonitoringEndToEndFixture
import com.duluin.ftth.common.security.JwtClaims
import com.duluin.ftth.customer.LegacyOnuTestFixture
import com.jayway.jsonpath.JsonPath
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.security.oauth2.jwt.JwtDecoder
import javax.sql.DataSource

abstract class WarehouseDiscoveryFixture : MonitoringEndToEndFixture() {
    @Autowired private lateinit var dataSource: DataSource
    @Autowired private lateinit var jwtDecoder: JwtDecoder

    protected data class Legacy(val customer: String, val onu: String, val serial: String)
    protected fun tenantId(token: String): java.util.UUID = java.util.UUID.fromString(jwtDecoder.decode(token).getClaimAsString(JwtClaims.TENANT_ID))

    protected fun customer(token: String): String = JsonPath.read(post("/api/customers", token,
        """{"code":"C-${uniq()}","name":"Discovery fixture","address":"Test","location":{"longitude":106.99,"latitude":-6.24}}"""), "$.id")

    protected fun legacy(token: String): Legacy {
        val customer = customer(token)
        val serial = "DISC-${uniq().uppercase()}"
        return Legacy(customer, LegacyOnuTestFixture.stage(customer, serial), serial)
    }

    protected fun scalar(token: String, sql: String): String = dataSource.connection.use { connection ->
        connection.autoCommit = false
        try {
            connection.prepareStatement("SELECT set_config('app.tenant_id',?,true)").use {
                it.setString(1, jwtDecoder.decode(token).getClaimAsString(JwtClaims.TENANT_ID))
                it.execute()
            }
            connection.createStatement().use { statement ->
                statement.executeQuery(sql).use { rows -> check(rows.next()); rows.getString(1) }
            }
        } finally {
            connection.rollback()
        }
    }
}

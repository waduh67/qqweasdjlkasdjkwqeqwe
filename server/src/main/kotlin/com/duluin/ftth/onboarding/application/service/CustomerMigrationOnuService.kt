package com.duluin.ftth.onboarding.application.service

import com.duluin.ftth.common.domain.error.ConflictException
import com.duluin.ftth.common.domain.error.ValidationException
import com.duluin.ftth.common.infrastructure.audit.AuditRecorder
import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.customer.CustomerReadAccessApi
import jakarta.persistence.EntityManager
import org.hibernate.Session
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

data class MigrationOnuView(val onuId: UUID, val serialNumber: String, val oltCode: String,
    val ponPortLabel: String?, val linkedAt: Instant)

@Service
class CustomerMigrationOnuService(private val entityManager: EntityManager,
    private val visibility: CustomerReadAccessApi, private val audit: AuditRecorder) {
    @Transactional
    fun link(customerId: UUID, serialNumber: String): List<MigrationOnuView> {
        visibility.requireVisibleCustomer(customerId)
        register(customerId, serialNumber)
        return list(customerId)
    }

    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    fun register(customerId: UUID, serialNumber: String) {
        val serial = serialNumber.trim().uppercase(java.util.Locale.ROOT)
        if (serial.isEmpty() || serial.length > 60) throw ValidationException("Nomor seri ONU tidak valid")
        val previous = list(customerId).any { it.serialNumber == serial }
        val id = try {
            entityManager.unwrap(Session::class.java).doReturningWork { connection ->
                connection.prepareStatement("SELECT customer_link_migrated_onu(?,?)").use { query ->
                    query.setObject(1, customerId); query.setString(2, serial)
                    query.executeQuery().use { rows -> check(rows.next()); rows.getObject(1, UUID::class.java) }
                }
            }
        } catch (error: org.hibernate.JDBCException) {
            if (error.sqlException.sqlState in setOf("P0001", "23505"))
                throw ConflictException("ONU tidak tersedia untuk dihubungkan. Periksa hasil deteksi, pelanggan, dan aset gudangnya.")
            throw error
        }
        if (!previous) audit.record("customer.migration.onu_linked", "Customer", customerId, TenantContext.tenantId(),
            mapOf("onuId" to id.toString(), "serialNumber" to serial))
    }

    fun list(customerId: UUID): List<MigrationOnuView> = entityManager.unwrap(Session::class.java).doReturningWork { connection ->
        connection.prepareStatement("""SELECT onu_id,serial_number,olt_code,pon_port_label,linked_at
            FROM customer_migration_onu WHERE tenant_id=? AND customer_id=? ORDER BY linked_at,onu_id""").use { query ->
            query.setObject(1, TenantContext.tenantId()); query.setObject(2, customerId)
            query.executeQuery().use { rows -> buildList { while (rows.next()) add(MigrationOnuView(
                rows.getObject(1, UUID::class.java), rows.getString(2), rows.getString(3), rows.getString(4), rows.getTimestamp(5).toInstant())) } }
        }
    }
}

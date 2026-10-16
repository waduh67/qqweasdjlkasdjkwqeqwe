package com.duluin.ftth.onboarding.application.service

import com.duluin.ftth.bng.BngApi
import com.duluin.ftth.bng.ProvisionAccessSpec
import com.duluin.ftth.common.domain.error.ConflictException
import com.duluin.ftth.common.domain.error.NotFoundException
import com.duluin.ftth.common.domain.error.ValidationException
import com.duluin.ftth.common.infrastructure.audit.AuditRecorder
import com.duluin.ftth.common.security.SecretCipher
import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.customer.*
import com.duluin.ftth.onboarding.adapter.outbound.persistence.CustomerMigrationJpaEntity
import com.duluin.ftth.onboarding.adapter.outbound.persistence.CustomerMigrationJpaRepository
import tools.jackson.databind.ObjectMapper
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.MessageDigest
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

enum class MigrationStatus { ACTIVE, SUSPENDED }
enum class MigrationSource { MANUAL, CSV, MIKROTIK }
data class CustomerMigrationCommand(
    val operationKey: String,
    val customer: RegisterCustomerCommand,
    val status: MigrationStatus,
    val installationDate: LocalDate? = null,
    val firstInvoiceOn: LocalDate? = null,
    val billingDay: Int? = null,
    val source: MigrationSource = MigrationSource.MANUAL,
    val username: String? = null,
    val password: String? = null,
    val authType: String = "PPPOE",
    val nasId: UUID? = null,
    val framedIp: String? = null,
    val onuSerial: String? = null,
)
data class CustomerMigrationView(
    val customerId: UUID, val subscriptionId: UUID, val source: String,
    val username: String?, val authType: String, val nasId: UUID?, val framedIp: String?,
    val hasPassword: Boolean, val accessId: UUID?, val billingManaged: Boolean,
    val firstInvoiceOn: LocalDate?, val billingDay: Int?,
    val onus: List<MigrationOnuView> = emptyList(),
)
data class MigrationRadiusCommand(val nasId: UUID, val username: String, val password: String? = null, val authType: String = "PPPOE", val framedIp: String? = null)

/** Records existing service without emitting installation or network activation events. */
interface CustomerMigrationRegistration { fun register(command: CustomerMigrationCommand): CustomerMigrationView }

@Service
class CustomerMigrationService(
    private val records: CustomerMigrationJpaRepository,
    private val customers: CustomerApi,
    private val visibility: CustomerReadAccessApi,
    private val bng: BngApi,
    private val cipher: SecretCipher,
    private val mapper: ObjectMapper,
    private val entityManager: EntityManager,
    private val audit: AuditRecorder,
    private val onus: CustomerMigrationOnuService,
) : CustomerMigrationRegistration {
    @Transactional
    override fun register(command: CustomerMigrationCommand): CustomerMigrationView {
        require(command.operationKey.length in 1..240) { "Identitas permintaan tidak valid" }
        val username = command.username?.trim()?.takeIf(String::isNotEmpty)
        if (username != null && username.length > 200) throw ValidationException("Username maksimal 200 karakter")
        if (command.authType !in setOf("PPPOE", "HOTSPOT", "DHCP", "STATIC")) throw ValidationException("Jenis koneksi tidak valid")
        val sourceKey = username?.let { "username:${it.lowercase()}" }
            ?: command.customer.code?.trim()?.takeIf(String::isNotEmpty)?.let { "code:${it.uppercase()}" }
            ?: "manual:${command.operationKey}"
        if (sourceKey.length > 240) throw ValidationException("Identitas pelanggan terlalu panjang")
        // Serialize registrations in one tenant, including retries with changed usernames.
        lock("register")
        val hashPayload = mapper.valueToTree<tools.jackson.databind.node.ObjectNode>(command)
        if (command.onuSerial == null) hashPayload.remove("onuSerial")
        val hash = MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsBytes(hashPayload)).joinToString("") { "%02x".format(it) }
        records.findByOperationKey(command.operationKey)?.let {
            if (it.requestHash != hash) throw ConflictException("Permintaan ini sudah digunakan untuk data berbeda")
            return view(it)
        }
        if (records.findBySourceKey(sourceKey) != null || username?.let { bng.findAccessByUsername(it) } != null) {
            throw ConflictException("Pelanggan sudah terdaftar")
        }
        if (command.nasId != null && !bng.hasNas(command.nasId)) throw ValidationException("BRAS tidak ditemukan")
        validateDates(command.installationDate, command.firstInvoiceOn, command.billingDay)
        val registered = customers.migrateCustomer(MigrateCustomerCommand(command.customer,
            command.status == MigrationStatus.SUSPENDED, command.installationDate?.atStartOfDay()?.toInstant(ZoneOffset.UTC),
            command.firstInvoiceOn, command.billingDay))
        val record = records.save(CustomerMigrationJpaEntity().apply {
            customerId = registered.customerId; subscriptionId = registered.subscriptionId
            operationKey = command.operationKey; requestHash = hash; this.sourceKey = sourceKey; source = command.source.name
            this.username = username; secretCiphertext = command.password?.takeIf(String::isNotEmpty)?.let(cipher::encrypt)
            authType = command.authType; nasId = command.nasId; framedIp = command.framedIp
        })
        command.onuSerial?.trim()?.takeIf(String::isNotEmpty)?.let {
            entityManager.flush()
            onus.register(registered.customerId, it)
        }
        audit.record("customer.migrated", "Customer", record.customerId, TenantContext.tenantId(),
            mapOf("source" to record.source, "status" to command.status.name, "billingManaged" to (command.firstInvoiceOn != null)))
        return view(record)
    }

    @Transactional
    fun get(customerId: UUID): CustomerMigrationView? {
        visibility.requireVisibleCustomer(customerId)
        return records.findByCustomerId(customerId)?.let(::view)
    }

    @Transactional
    fun startBilling(customerId: UUID, firstInvoiceOn: LocalDate, billingDay: Int?): CustomerMigrationView {
        visibility.requireVisibleCustomer(customerId)
        lock("customer:$customerId")
        val record = requireRecord(customerId)
        val existing = customers.findBillableSubscription(record.subscriptionId)
        if (existing?.billingManaged == true && existing.billingStartsOn == firstInvoiceOn && existing.billingDayOfMonth == (billingDay ?: firstInvoiceOn.dayOfMonth)) return view(record)
        validateDates(null, firstInvoiceOn, billingDay)
        customers.startMigrationBilling(record.subscriptionId, firstInvoiceOn, billingDay)
        audit.record("customer.migration.billing_started", "Customer", customerId, TenantContext.tenantId(), mapOf("firstInvoiceOn" to firstInvoiceOn.toString()))
        return view(record)
    }

    @Transactional
    fun prepareRadius(customerId: UUID, command: MigrationRadiusCommand): CustomerMigrationView {
        visibility.requireVisibleCustomer(customerId)
        lock("customer:$customerId")
        val record = requireRecord(customerId)
        if (record.accessId != null) return view(record)
        val subscription = customers.findSubscription(record.subscriptionId) ?: throw NotFoundException("Langganan tidak ditemukan")
        if (subscription.status !in setOf("ACTIVE", "ISOLATED")) throw ConflictException("Langganan sudah tidak berjalan")
        val username = command.username.trim()
        if (username.isEmpty()) throw ValidationException("Username wajib diisi")
        val secret = command.password?.takeIf(String::isNotEmpty) ?: record.secretCiphertext?.let(cipher::decrypt)
        if (command.authType in setOf("PPPOE", "HOTSPOT") && secret.isNullOrEmpty()) throw ValidationException("Password wajib diisi")
        val access = bng.provisionAccess(ProvisionAccessSpec(record.subscriptionId, username, secret,
            subscription.planId ?: throw ValidationException("Pilih paket langganan"), command.nasId, command.authType, command.framedIp))
        record.accessId = access.accessId
        record.username = username; record.authType = command.authType; record.nasId = command.nasId; record.framedIp = command.framedIp
        record.secretCiphertext = null
        records.save(record)
        audit.record("customer.migration.radius_prepared", "Customer", customerId, TenantContext.tenantId(), mapOf("accessId" to access.accessId.toString()))
        return view(record)
    }

    private fun requireRecord(customerId: UUID) = records.findByCustomerId(customerId) ?: throw NotFoundException("Data migrasi tidak ditemukan")
    private fun view(record: CustomerMigrationJpaEntity): CustomerMigrationView {
        val billing = customers.findBillableSubscription(record.subscriptionId) ?: throw NotFoundException("Langganan tidak ditemukan")
        return CustomerMigrationView(record.customerId, record.subscriptionId, record.source, record.username, record.authType,
            record.nasId, record.framedIp, record.secretCiphertext != null, record.accessId, billing.billingManaged, billing.billingStartsOn, billing.billingDayOfMonth, onus.list(record.customerId))
    }
    private fun lock(key: String) {
        entityManager.createNativeQuery("SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))")
            .setParameter("key", "customer-migration:${TenantContext.tenantId()}:$key").resultList
    }
    private fun validateDates(installed: LocalDate?, firstInvoice: LocalDate?, day: Int?) {
        if (installed != null && installed.isAfter(LocalDate.now())) throw ValidationException("Tanggal mulai layanan tidak boleh di masa depan")
        if (firstInvoice != null && firstInvoice.isBefore(LocalDate.now())) throw ValidationException("Tanggal tagihan pertama tidak boleh sebelum hari ini")
        if (day != null && day !in 1..31) throw ValidationException("Tanggal tagih harus antara 1 dan 31")
    }
}

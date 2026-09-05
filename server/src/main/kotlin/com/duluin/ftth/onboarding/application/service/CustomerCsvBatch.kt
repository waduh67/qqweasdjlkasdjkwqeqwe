package com.duluin.ftth.onboarding.application.service

import com.duluin.ftth.onboarding.adapter.outbound.persistence.CustomerImportBatchJpaEntity
import com.duluin.ftth.onboarding.adapter.outbound.persistence.CustomerImportBatchJpaRepository
import com.duluin.ftth.onboarding.adapter.outbound.persistence.CustomerImportErrorJpaEntity
import com.duluin.ftth.onboarding.adapter.outbound.persistence.CustomerImportErrorJpaRepository
import com.duluin.ftth.onboarding.adapter.outbound.persistence.CustomerImportStagingRowJpaEntity
import com.duluin.ftth.onboarding.adapter.outbound.persistence.CustomerImportStagingRowJpaRepository
import com.duluin.ftth.onboarding.adapter.outbound.persistence.CustomerImportOutboxJpaEntity
import com.duluin.ftth.onboarding.adapter.outbound.persistence.CustomerImportOutboxJpaRepository
import com.duluin.ftth.onboarding.application.port.inbound.CustomerImportRow
import com.duluin.ftth.onboarding.application.port.inbound.ImportCustomersCommand
import com.duluin.ftth.onboarding.application.port.inbound.ImportCustomersResult
import com.duluin.ftth.onboarding.application.port.inbound.ImportCustomersUseCase
import com.duluin.ftth.onboarding.application.port.inbound.ImportMode
import com.duluin.ftth.common.tenant.TenantContext
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import com.duluin.ftth.common.domain.error.ConflictException
import com.duluin.ftth.bng.CredentialHandle
import com.duluin.ftth.onboarding.adapter.outbound.persistence.CustomerImportCredentialVault
import tools.jackson.databind.ObjectMapper
import tools.jackson.core.type.TypeReference
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.io.Reader
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.time.Duration
import java.util.UUID
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

data class CsvImportError(val row: Int, val column: String?, val code: String, val message: String)
data class ParsedCustomerCsv(val sha256: String, val rows: List<CustomerImportRow>, val errors: List<CsvImportError>)

object CustomerCsvParser {
    const val MAX_BYTES: Long = 25L * 1024 * 1024
    const val MAX_ROWS = 100_000
    private val columns = setOf("name", "phone", "address", "package_name", "connection_type", "installation_date", "mikrotik_username", "mikrotik_password", "email", "router_name", "framed_ip", "id_card_number", "next_billing", "latitude", "longitude", "notes")

    fun parse(input: InputStream, byteCount: Long): ParsedCustomerCsv {
        require(byteCount in 1..MAX_BYTES) { "Ukuran CSV harus antara 1 byte dan 25 MiB" }
        val bytes = input.use { source ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            var total = 0L
            while (true) {
                val read = source.read(buffer)
                if (read < 0) break
                total += read
                require(total <= MAX_BYTES) { "Ukuran CSV melebihi batas 25 MiB" }
                output.write(buffer, 0, read)
            }
            output.toByteArray()
        }
        require(bytes.size.toLong() == byteCount) { "Ukuran berkas berubah selama unggah" }
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).toHex()
        val payload = if (bytes.take(3) == listOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())) bytes.copyOfRange(3, bytes.size) else bytes
        val decoder = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        val text = decoder.decode(ByteBuffer.wrap(payload)).toString()
        return parseRecords(BufferedReader(InputStreamReader(text.byteInputStream(StandardCharsets.UTF_8), StandardCharsets.UTF_8)), hash)
    }

    private fun parseRecords(reader: Reader, hash: String): ParsedCustomerCsv {
        val records = mutableListOf<List<String>>(); val row = mutableListOf<String>(); val field = StringBuilder(); var quoted = false; var quoteClosed = false
        fun fieldEnd() { row += field.toString(); field.setLength(0); quoteClosed = false }
        fun rowEnd() { fieldEnd(); if (row.any(String::isNotBlank)) records += row.toList(); row.clear() }
        while (true) {
            val value = reader.read(); if (value < 0) break; val ch = value.toChar()
            if (quoted) {
                if (ch == '"') {
                    reader.mark(1); val next = reader.read()
                    if (next == '"'.code) field.append('"') else { quoted = false; quoteClosed = true; if (next >= 0) reader.reset() }
                } else field.append(ch)
            } else when (ch) {
                '"' -> if (field.isEmpty() && !quoteClosed) quoted = true else field.append(ch)
                ',' -> fieldEnd(); '\n' -> rowEnd(); '\r' -> Unit; else -> field.append(ch)
            }
        }
        if (quoted) return ParsedCustomerCsv(hash, emptyList(), listOf(CsvImportError(records.size + 1, null, "MALFORMED_CSV", "Kutip CSV tidak ditutup")))
        if (field.isNotEmpty() || row.isNotEmpty()) rowEnd()
        if (records.isEmpty()) return ParsedCustomerCsv(hash, emptyList(), listOf(CsvImportError(1, null, "EMPTY", "CSV kosong")))
        if (records.size - 1 > MAX_ROWS) return ParsedCustomerCsv(hash, emptyList(), listOf(CsvImportError(MAX_ROWS + 2, null, "ROW_LIMIT", "Jumlah baris melebihi 100000")))
        val header = records.first().map { it.trim().lowercase() }
        val errors = header.distinct().filter { it !in columns }.map { CsvImportError(1, it, "UNKNOWN_COLUMN", "Kolom tidak dikenal") }.toMutableList()
        val index = header.withIndex().associate { it.value to it.index }; val seen = mutableSetOf<String>(); val rows = mutableListOf<CustomerImportRow>()
        records.drop(1).forEachIndexed { offset, cells ->
            val rowNumber = offset + 2
            fun at(name: String): String? = index[name]?.let { cells.getOrNull(it)?.trim()?.takeIf(String::isNotEmpty) }
            val username = at("mikrotik_username")
            if (username != null && !seen.add(username.lowercase())) errors += CsvImportError(rowNumber, "mikrotik_username", "DUPLICATE_KEY", "Kunci bisnis duplikat")
            fun decimal(name: String): Double? = at(name)?.toDoubleOrNull() ?: at(name)?.let { errors += CsvImportError(rowNumber, name, "INVALID_NUMBER", "Angka tidak valid"); null }
            fun integer(name: String): Int? = at(name)?.toIntOrNull() ?: at(name)?.let { errors += CsvImportError(rowNumber, name, "INVALID_NUMBER", "Bilangan bulat tidak valid"); null }
            val date = at("installation_date")?.let { runCatching { java.time.LocalDate.parse(it) }.getOrElse { errors += CsvImportError(rowNumber, "installation_date", "INVALID_DATE", "Tanggal tidak valid"); null } }
            val latitude = decimal("latitude")
            val longitude = decimal("longitude")
            val unlocated = latitude == 0.0 && longitude == 0.0
            rows += CustomerImportRow(at("name"), at("phone"), at("address"), at("package_name"), at("connection_type"), date, username, at("mikrotik_password"), at("email"), at("router_name"), at("id_card_number"), integer("next_billing"), latitude?.takeUnless { unlocated }, longitude?.takeUnless { unlocated }, at("framed_ip"))
        }
        return ParsedCustomerCsv(hash, rows, errors)
    }
    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
}

enum class CustomerImportBatchState { STAGED, PROCESSING, COMMITTED, CANCELLED, FAILED, RETRYABLE_FAILED, PERMANENT_FAILED, PURGED }
data class CustomerImportBatchView(val id: UUID, val sha256: String, val mode: ImportMode, val state: CustomerImportBatchState, val errors: List<CsvImportError>, val result: ImportCustomersResult?, val createdAt: Instant)

@Service
class CustomerImportBatchService(
    private val importer: ImportCustomersUseCase,
    private val batches: CustomerImportBatchJpaRepository,
    private val staging: CustomerImportStagingRowJpaRepository,
    private val errors: CustomerImportErrorJpaRepository,
    private val mapper: ObjectMapper,
    private val outbox: CustomerImportOutboxJpaRepository,
    private val credentialVault: CustomerImportCredentialVault,
) {
    @Transactional
    fun stage(operationKey: String, parsed: ParsedCustomerCsv, mode: ImportMode): CustomerImportBatchView {
        require(operationKey.length in 1..240) { "operation_key tidak valid" }
        require(TenantContext.tenantIdOrNull() != null) { "Tenant context diperlukan" }
        val byOperation = batches.findByOperationKey(operationKey)
        if (byOperation != null) require(byOperation.sha256 == parsed.sha256 && byOperation.mode == mode.name) { "operation_key sudah digunakan untuk berkas atau mode berbeda" }
        val byFile = batches.findBySha256(parsed.sha256)
        if (byFile != null) require(byFile.mode == mode.name) { "Berkas sudah digunakan dalam mode berbeda" }
        if (byOperation != null) return view(byOperation)
        if (byFile != null) return view(byFile)
        val entity = CustomerImportBatchJpaEntity().apply {
            this.operationKey = operationKey; sha256 = parsed.sha256; this.mode = mode.name; state = CustomerImportBatchState.STAGED
            objectKey = "customer-import/${parsed.sha256}"; rowCount = parsed.rows.size; schemaVersion = 1; importType = "CUSTOMERS_CSV"; retentionUntil = Instant.now().plus(Duration.ofDays(30))
        }
        try { batches.saveAndFlush(entity) } catch (ex: DataIntegrityViolationException) {
            val concurrentOperation = batches.findByOperationKey(operationKey)
            if (concurrentOperation != null) {
                require(concurrentOperation.sha256 == parsed.sha256 && concurrentOperation.mode == mode.name) { "operation_key sudah digunakan untuk berkas atau mode berbeda" }
                return view(concurrentOperation)
            }
            val concurrentFile = batches.findBySha256(parsed.sha256)
            if (concurrentFile != null) {
                require(concurrentFile.mode == mode.name) { "Berkas sudah digunakan dalam mode berbeda" }
                return view(concurrentFile)
            }
            throw ex
        }
        parsed.rows.forEachIndexed { index, row -> staging.save(CustomerImportStagingRowJpaEntity().apply { batchId = entity.id; rowNumber = index + 2; credentialHandleId = credentialVault.seal(row.mikrotikPassword)?.id; payload = mapper.writeValueAsString(row.copy(mikrotikPassword = null)) }) }
        parsed.errors.forEach { error -> errors.save(CustomerImportErrorJpaEntity().apply { batchId = entity.id; rowNumber = error.row; columnName = error.column; code = error.code; message = error.message }) }
        return view(entity, parsed.errors)
    }

    @Transactional(readOnly = true) fun status(id: UUID): CustomerImportBatchView = view(batches.findById(id).orElseThrow())
    @Transactional fun cancel(id: UUID): CustomerImportBatchView = transition(id, CustomerImportBatchState.CANCELLED)
    @Transactional fun retry(id: UUID, commitOperationKey: String, commitHash: String): CustomerImportBatchView = commit(id, commitOperationKey, commitHash)

    @Transactional
    fun commit(id: UUID, commitOperationKey: String, commitHash: String): CustomerImportBatchView {
        require(commitOperationKey.length in 1..240) { "commit_operation_key tidak valid" }
        require(commitHash.matches(Regex("[0-9a-fA-F]{64}"))) { "commit_hash tidak valid" }
        val entity = batches.findForUpdate(id) ?: throw NoSuchElementException("Batch tidak ditemukan")
        if (commitHash != entity.sha256) throw ConflictException("commit_hash tidak cocok dengan berkas" )
        if (entity.commitOperationKey != null) {
            if (entity.commitOperationKey != commitOperationKey || entity.commitHash != commitHash) throw ConflictException("commit operation sudah digunakan untuk payload berbeda")
            return view(entity)
        }
        if (entity.state == CustomerImportBatchState.COMMITTED || entity.state == CustomerImportBatchState.PROCESSING) throw ConflictException("Batch sudah memiliki commit operation")
        require(entity.state == CustomerImportBatchState.STAGED || entity.state == CustomerImportBatchState.RETRYABLE_FAILED) { "Batch tidak siap di-commit" }
        val batchErrors = errors.findAllByBatchIdOrderByRowNumber(id).map { CsvImportError(it.rowNumber, it.columnName, it.code, it.message) }
        if (batchErrors.isNotEmpty()) { entity.state = CustomerImportBatchState.PERMANENT_FAILED; entity.errorCode = "VALIDATION_FAILED"; return view(batches.save(entity), batchErrors) }
        entity.commitOperationKey = commitOperationKey; entity.commitHash = commitHash; entity.state = CustomerImportBatchState.PROCESSING
        batches.saveAndFlush(entity)
        outbox.save(CustomerImportOutboxJpaEntity().apply { batchId = id; operationKey = commitOperationKey; eventType = "CUSTOMER_IMPORT_PROMOTE"; payload = mapper.writeValueAsString(mapOf("batchId" to id, "commitHash" to commitHash)) })
        return view(entity)
    }

    @Transactional(readOnly = true)
    fun report(id: UUID): String = view(batches.findById(id).orElseThrow()).toSafeCsv()

    private fun transition(id: UUID, state: CustomerImportBatchState): CustomerImportBatchView {
        val entity = batches.findForUpdate(id) ?: throw NoSuchElementException("Batch tidak ditemukan")
        require(entity.state == CustomerImportBatchState.STAGED) { "Batch tidak dapat dibatalkan" }
        entity.state = state; return view(batches.save(entity))
    }
    private fun view(entity: CustomerImportBatchJpaEntity, knownErrors: List<CsvImportError> = errors.findAllByBatchIdOrderByRowNumber(entity.id).map { CsvImportError(it.rowNumber, it.columnName, it.code, it.message) }): CustomerImportBatchView = CustomerImportBatchView(entity.id, entity.sha256, ImportMode.valueOf(entity.mode), entity.state, knownErrors, entity.result?.let { mapper.readValue(it, object : TypeReference<ImportCustomersResult>() {}) }, entity.createdAt)
}

@Component
class CustomerImportPromotionScheduler(private val tenantApi: com.duluin.ftth.tenancy.TenantApi, private val worker: CustomerImportPromotionPort) {
    @Scheduled(fixedDelayString = "\${ftth.onboarding.import-promotion-delay-ms:1000}")
    fun promoteOne() {
        tenantApi.findActiveTenantIds().forEach { tenantId ->
            runCatching { com.duluin.ftth.common.tenant.TenantContext.runAs(tenantId) { worker.promoteOne() } }
        }
    }
}

interface CustomerImportPromotionPort {
    fun promoteOne()
}

@Component
class CustomerImportPromotionWorker(
    private val outbox: CustomerImportOutboxJpaRepository,
    private val batches: CustomerImportBatchJpaRepository,
    private val staging: CustomerImportStagingRowJpaRepository,
    private val importer: ImportCustomersUseCase,
    private val mapper: ObjectMapper,
    private val credentialVault: CustomerImportCredentialVault,
) : CustomerImportPromotionPort {
    @Transactional
    override fun promoteOne() {
        val event = outbox.findFirstByPublishedAtIsNullOrderByCreatedAt() ?: return
        val entity = batches.findForUpdate(event.batchId) ?: return
        if (event.publishedAt != null) return
        if (entity.state == CustomerImportBatchState.COMMITTED) { event.publishedAt = Instant.now(); outbox.save(event); return }
        try {
            val stagedRows = staging.findAllByBatchIdOrderByRowNumber(entity.id)
            val rows = stagedRows.map { row ->
                val parsed = mapper.readValue(row.payload, object : TypeReference<CustomerImportRow>() {})
                row.credentialHandleId?.let { parsed.copy(mikrotikPassword = credentialVault.resolve(CredentialHandle(it))) } ?: parsed
            }
            val result = importer.importCustomers(ImportCustomersCommand(rows, mode = ImportMode.valueOf(entity.mode), operationKey = entity.commitOperationKey))
            entity.state = CustomerImportBatchState.COMMITTED; entity.result = mapper.writeValueAsString(result); entity.committedAt = Instant.now(); entity.errorCode = null
            batches.save(entity); event.publishedAt = Instant.now(); outbox.save(event)
            stagedRows.mapNotNull { it.credentialHandleId }.forEach { credentialVault.consume(CredentialHandle(it)) }
        } catch (_: RuntimeException) {
            event.attemptCount += 1; event.lastErrorCode = "IMPORT_FAILED"; outbox.save(event)
            entity.state = if (event.attemptCount >= 3) CustomerImportBatchState.PERMANENT_FAILED else CustomerImportBatchState.RETRYABLE_FAILED
            entity.errorCode = event.lastErrorCode; batches.save(entity)
            if (entity.state == CustomerImportBatchState.PERMANENT_FAILED) staging.findAllByBatchIdOrderByRowNumber(entity.id).mapNotNull { it.credentialHandleId }.forEach { credentialVault.purge(CredentialHandle(it)) }
        }
    }
}

private fun CustomerImportBatchView.toSafeCsv(): String {
    fun safe(value: String): String = if (value.firstOrNull() in setOf('=', '+', '-', '@')) "'$value" else value.replace("\"", "\"\"").let { if (it.any { ch -> ch == ',' || ch == '\n' || ch == '\r' }) "\"$it\"" else it }
    return buildString {
        append("row,column,code,message\r\n")
        errors.forEach { append(it.row).append(',').append(safe(it.column.orEmpty())).append(',').append(safe(it.code)).append(',').append(safe(it.message)).append("\r\n") }
        result?.rows?.forEachIndexed { index, row -> append(index + 1).append(",username,status,message\r\n"); append(safe(row.username)).append(',').append(row.status).append(',').append(safe(row.message.orEmpty())).append("\r\n") }
    }
}

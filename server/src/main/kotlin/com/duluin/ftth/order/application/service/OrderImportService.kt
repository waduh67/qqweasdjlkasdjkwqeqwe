package com.duluin.ftth.order.application.service

import com.duluin.ftth.catalog.CatalogApi
import com.duluin.ftth.catalog.PlanCommercialRef
import com.duluin.ftth.common.domain.error.ConflictException
import com.duluin.ftth.common.domain.error.NotFoundException
import com.duluin.ftth.common.domain.error.ValidationException
import com.duluin.ftth.common.security.CurrentUserProvider
import com.duluin.ftth.order.CreateOrderCommand
import com.duluin.ftth.order.OperationCommand
import com.duluin.ftth.order.OrderLineCommand
import com.duluin.ftth.order.OrderTransition
import com.duluin.ftth.order.OrderTransitionCommand
import com.duluin.ftth.order.OrderView
import com.duluin.ftth.order.ServiceAddress
import com.duluin.ftth.order.application.port.inbound.OrderImportBatchDetailView
import com.duluin.ftth.order.application.port.inbound.OrderImportBatchStatusView
import com.duluin.ftth.order.application.port.inbound.OrderImportBatchSummaryView
import com.duluin.ftth.order.application.port.inbound.OrderImportLimits
import com.duluin.ftth.order.application.port.inbound.OrderImportRowStatusView
import com.duluin.ftth.order.application.port.inbound.OrderImportRowView
import com.duluin.ftth.order.application.port.inbound.OrderImportUseCase
import com.duluin.ftth.order.application.port.inbound.SystemOrderUseCase
import com.duluin.ftth.order.application.port.outbound.OrderImportRepository
import com.duluin.ftth.order.application.port.outbound.OrderLeadRepository
import com.duluin.ftth.order.application.port.outbound.OrderRepository
import com.duluin.ftth.order.domain.model.LeadSource
import com.duluin.ftth.order.domain.model.OrderImportBatch
import com.duluin.ftth.order.domain.model.OrderImportBatchStatus
import com.duluin.ftth.order.domain.model.OrderImportRow
import com.duluin.ftth.order.domain.model.OrderImportRowStatus
import com.duluin.ftth.order.domain.model.OrderLead
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Lazy
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

/**
 * Impor massal pesanan dari CSV, dua langkah: praview lalu commit.
 *
 * Bentuk kelas ini ditentukan tiga jebakan yang semuanya pernah memakan korban di repo ini:
 *
 * 1. JEBAKAN ROLLBACK-ONLY. Menangkap exception yang dilempar method `@Transactional` bean lain
 *    TIDAK menyelamatkan pemanggil: `TransactionInterceptor` sudah memanggil `setRollbackOnly()`,
 *    dan commit di luar meledak dengan `UnexpectedRollbackException`. Satu baris rusak akan
 *    menggagalkan 499 baris lain — persis kebalikan dari janji fitur ini. Karena itu setiap
 *    baris dieksekusi lewat [commitRow] yang `REQUIRES_NEW`, dipanggil LEWAT PROXY ([self]),
 *    dan barulah [commit] boleh menangkap kegagalannya.
 *
 * 2. PANGGILAN LEWAT `this`. `this.commitRow(...)` melewati proxy dan `@Transactional` di
 *    bawahnya DIAM-DIAM tidak berlaku — isolasi per baris hilang tanpa satu baris galat pun.
 *
 * 3. IDEMPOTENSI DUA TINGKAT. Tingkat berkas dijaga `uq_order_import_batch_content`; tingkat
 *    baris dijaga `order_operation` lewat `operation_key` = sidik jari barisnya. Keduanya perlu:
 *    operator yang mengirim ulang berkas yang sama DENGAN beberapa baris tambahan di bawahnya
 *    adalah kasus yang paling sering terjadi, dan penjaga tingkat berkas tak menolongnya.
 */
@Suppress("LongParameterList", "TooManyFunctions", "LargeClass")
@Service
class OrderImportService(
    private val imports: OrderImportRepository,
    private val orders: OrderRepository,
    private val leads: OrderLeadRepository,
    private val catalog: CatalogApi,
    private val system: SystemOrderUseCase,
    private val currentUser: CurrentUserProvider,
    /**
     * Rujukan ke diri sendiri LEWAT PROXY Spring, `@Lazy` supaya bukan dependensi melingkar saat
     * bean-nya dibuat. WAJIB dipakai untuk [commitRow]/[failRow]/[persistPreview] — lihat
     * jebakan 1 dan 2 di dokumentasi kelas.
     */
    @Lazy private val self: OrderImportService? = null,
) : OrderImportUseCase {

    private val log = LoggerFactory.getLogger(javaClass)

    // -------------------------------------------------------------------------------------
    // Praview
    // -------------------------------------------------------------------------------------

    /**
     * SENGAJA TIDAK `@Transactional`. Method ini menangkap [DataIntegrityViolationException] dari
     * [persistPreview]; kalau ia sendiri transaksional, transaksinya sudah ditandai rollback-only
     * saat tangkapan terjadi dan commit-nya meledak belakangan.
     */
    override fun preview(fileName: String, bytes: ByteArray): OrderImportBatchDetailView {
        val user = currentUser.current()
        requireWithinSizeLimit(bytes.size.toLong())
        val contentHash = sha256Hex(bytes)
        val proxied = self ?: this

        /*
         * Gerbang idempotensi tingkat berkas ADA DI DEPAN, bukan hanya disandarkan pada unique
         * index. Operator yang ragu apakah unggahannya tadi berhasil PASTI mengunggah lagi; tanpa
         * gerbang ini ia menerima galat "duplikat" alih-alih praview yang sedang ia cari.
         */
        proxied.existingBatch(user.tenantId, contentHash)?.let { return it }

        val csv = OrderImportCsvParser.parse(bytes)
        if (csv.records.size > OrderImportLimits.MAX_DATA_ROWS) {
            throw ValidationException(
                "Berkas berisi ${csv.records.size} baris data, melebihi batas " +
                    "${OrderImportLimits.MAX_DATA_ROWS} baris per impor. Pecah berkasnya lalu unggah bergantian.",
            )
        }

        return try {
            proxied.persistPreview(user.tenantId, user.userId, fileName, contentHash, bytes.size.toLong(), csv)
        } catch (ex: DataIntegrityViolationException) {
            // Dua operator mengunggah berkas yang sama nyaris bersamaan. Yang kalah balapan
            // memulangkan batch pemenangnya — bukan galat yang menyuruhnya mengulang.
            log.debug("Balapan unggah berkas impor {}: {}", contentHash, ex.message)
            proxied.existingBatch(user.tenantId, contentHash)
                ?: throw ConflictException("Berkas ini sedang diproses permintaan lain. Muat ulang halaman impor.")
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    fun existingBatch(tenantId: UUID, contentHash: String): OrderImportBatchDetailView? =
        imports.findBatchByContentHash(tenantId, contentHash)?.let { detail(it) }

    /**
     * Menulis hasil urai — dan HANYA hasil urai. Tak satu pun `order_record` atau `order_lead`
     * lahir di sini: itulah seluruh alasan praview ada.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun persistPreview(
        tenantId: UUID,
        actorId: UUID?,
        fileName: String,
        contentHash: String,
        byteSize: Long,
        csv: OrderImportCsv,
    ): OrderImportBatchDetailView {
        val columns = OrderImportColumns.of(csv.header)
        val now = Instant.now()
        val batch = OrderImportBatch.preview(
            tenantId = tenantId,
            fileName = fileName,
            contentHash = contentHash,
            byteSize = byteSize,
            delimiter = csv.delimiter,
            importedBy = actorId,
            now = now,
        )
        val seen = mutableSetOf<String>()
        val rows = csv.records.map { evaluate(tenantId, batch.id, columns, it, seen, now) }
        batch.summarizePreview(
            totalRows = rows.size,
            acceptedRows = rows.count { it.status == OrderImportRowStatus.ACCEPTED },
            // DUPLICATE ikut dihitung sebagai ditolak: dari sudut pandang operator ia memang
            // baris yang tidak akan menghasilkan pesanan, dan memisahkannya jadi cacah ketiga
            // hanya membuat "diterima + ditolak ≠ total" yang tampak seperti bug.
            rejectedRows = rows.count {
                it.status == OrderImportRowStatus.REJECTED || it.status == OrderImportRowStatus.DUPLICATE
            },
        )
        imports.saveBatch(batch)
        imports.saveRows(rows)
        return detail(batch, rows)
    }

    // -------------------------------------------------------------------------------------
    // Pembacaan
    // -------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    override fun find(batchId: UUID): OrderImportBatchDetailView {
        val batch = imports.findBatch(batchId) ?: throw notFound()
        if (batch.tenantId != currentUser.current().tenantId) throw notFound()
        return detail(batch)
    }

    @Transactional(readOnly = true)
    override fun history(limit: Int): List<OrderImportBatchSummaryView> =
        imports.recentBatches(limit).map { it.toSummary() }

    // -------------------------------------------------------------------------------------
    // Commit
    // -------------------------------------------------------------------------------------

    /**
     * SENGAJA TIDAK `@Transactional` — lihat jebakan 1 di dokumentasi kelas. Method ini hanya
     * mengoordinasi; seluruh penulisan terjadi di [commitRow]/[failRow]/[finishCommit] yang
     * masing-masing punya transaksinya sendiri.
     */
    override fun commit(batchId: UUID): OrderImportBatchDetailView {
        val user = currentUser.current()
        val proxied = self ?: this
        val pending = proxied.pendingRowIds(batchId)
        // null = batch sudah pernah dijalankan. Memulangkan hasilnya apa adanya, bukan 409:
        // tombol "Jalankan" yang ditekan dua kali karena responsnya lambat adalah perilaku
        // normal, dan menghukumnya dengan galat cuma membuat operator mengunggah ulang berkas.
        if (pending == null) return proxied.find(batchId)

        pending.forEach { rowId ->
            try {
                proxied.commitRow(user.tenantId, user.userId, rowId)
            } catch (ex: Exception) {
                /*
                 * Aman ditangkap HANYA karena [commitRow] berjalan di transaksinya sendiri
                 * (`REQUIRES_NEW`) dan dipanggil lewat proxy. Kalau salah satu syarat itu
                 * hilang, tangkapan ini justru yang meruntuhkan seluruh berkas.
                 */
                log.warn("Baris impor {} gagal dieksekusi: {}", rowId, ex.message, ex)
                proxied.failRow(rowId, readable(ex))
            }
        }
        proxied.finishCommit(batchId)
        return proxied.find(batchId)
    }

    /**
     * Id baris yang harus dieksekusi, atau `null` bila batch-nya sudah COMMITTED.
     *
     * Yang dipulangkan ID, bukan entitas: setiap baris dibaca ulang di dalam transaksinya
     * sendiri. Objek yang dibawa menyeberang batas transaksi sudah terlepas dari persistence
     * context-nya, dan menyimpannya kembali akan menimpa perubahan baris lain.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    fun pendingRowIds(batchId: UUID): List<UUID>? {
        val batch = imports.findBatch(batchId) ?: throw notFound()
        if (batch.status == OrderImportBatchStatus.COMMITTED) return null
        return imports.findRows(batchId)
            .filter { it.status == OrderImportRowStatus.ACCEPTED }
            .map { it.id }
    }

    /**
     * Satu baris = satu transaksi. Persis seperti `PublicOrderIntakeService.submit`: prospek
     * dibuat, pesanannya menyusul, lalu LANGSUNG di-SUBMIT — pesanan yang berhenti di DRAFT tak
     * punya padanan status portal dan tak akan pernah bisa dilacak pemesannya.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun commitRow(tenantId: UUID, actorId: UUID?, rowId: UUID) {
        val row = imports.findRow(rowId) ?: throw notFound()
        // Sudah dikerjakan percobaan sebelumnya (mis. koneksi klien putus di tengah commit).
        if (row.status != OrderImportRowStatus.ACCEPTED) return
        val fingerprint = row.fingerprint
            ?: throw ValidationException("Baris ini tidak punya sidik jari; unggah ulang berkasnya")
        val planId = row.planId ?: throw ValidationException("Paket baris ini tidak diketahui")

        /*
         * Paket DIPERIKSA ULANG di sini, bukan dipercaya dari praview. Praview bisa dibuka
         * kemarin sore dan paketnya ditarik dari penjualan pagi ini; membuat pesanan untuk paket
         * yang sudah tak dijual berarti menjual sesuatu yang sudah tak ada.
         */
        val plan = catalog.findPlanCommercial(planId)?.takeIf { it.active }
            ?: throw ValidationException("Paket yang dipilih sudah tidak dijual saat impor dijalankan")

        /*
         * Gerbang idempotensi tingkat baris, DI DEPAN pembuatan prospek. Pembuatan `OrderLead`
         * tidak berada di bawah penjagaan operation key mana pun — tanpa gerbang ini, baris yang
         * pesanannya sudah pernah dibuat akan meninggalkan prospek yatim kedua di antrean
         * operator. Hazard yang sama dijelaskan di `PublicOrderIntakeService`.
         */
        val replayed = orders.findOutcome(tenantId, NAMESPACE, fingerprint)?.value
        val created = replayed ?: createOrder(tenantId, actorId, row, plan, fingerprint)

        /*
         * SUBMIT dijalankan JUGA di jalur replay. Percobaan sebelumnya bisa mati persis di antara
         * `create` dan `transition`; pesanan yang tertinggal di DRAFT tak punya padanan status
         * portal (lihat `portalStatusOf`), jadi nomornya selamanya menjawab "tidak ditemukan"
         * kepada orang yang melacaknya. Aman diulang karena kuncinya sendiri idempoten.
         */
        val submitted = system.transition(
            tenantId = tenantId,
            actorId = actorId,
            command = OrderTransitionCommand(
                orderId = created.id,
                transition = OrderTransition.SUBMIT,
                expectedRevision = created.revision,
                operation = OperationCommand(NAMESPACE, "$fingerprint:submit", fingerprint),
            ),
        )

        row.markCreated(created.id, created.leadId, submitted.orderNumber ?: created.orderNumber)
        imports.saveRow(row)
    }

    /**
     * Prospek + pesanan untuk satu baris. Dipisah dari [commitRow] supaya jalur replay tidak
     * melewatinya: [OrderLead] TIDAK berada di bawah penjagaan operation key mana pun, jadi
     * memanggil ini dua kali meninggalkan prospek yatim kedua di antrean operator meski
     * pesanannya tetap satu.
     */
    private fun createOrder(
        tenantId: UUID,
        actorId: UUID?,
        row: OrderImportRow,
        plan: PlanCommercialRef,
        fingerprint: String,
    ): OrderView {
        val lead = OrderLead.create(
            tenantId = tenantId,
            name = row.name.orEmpty(),
            phone = row.phone.orEmpty(),
            email = row.email,
            address = fullAddress(row),
            interestedPlanId = plan.planId,
            source = LeadSource.CSV_IMPORT,
            notes = row.notes,
        )
        leads.save(lead)
        return system.create(
            tenantId = tenantId,
            // Berbeda dari pintu publik: impor SELALU punya pelaku manusia, dan riwayat pesanan
            // harus bisa menjawab "siapa yang memasukkan 500 pesanan ini".
            actorId = actorId,
            command = CreateOrderCommand(
                customerId = null,
                leadId = lead.id,
                // Satu baris = paket yang dipilih. Perangkat dan biaya pemasangan ditambahkan
                // operator saat meninjau, sama seperti pesanan dari pintu publik.
                lines = listOf(OrderLineCommand(plan.planId, plan.packageName, 1)),
                serviceAddress = ServiceAddress(
                    address = row.address.orEmpty(),
                    city = row.city.orEmpty(),
                    postalCode = row.postalCode.orEmpty(),
                ),
                // Sidik jari dipakai APA ADANYA sebagai kunci, dan juga sebagai payloadHash:
                // keduanya lahir dari isi baris yang sama, jadi baris dengan isi berbeda
                // menghasilkan KUNCI berbeda — bukan konflik hash yang membingungkan.
                operation = OperationCommand(NAMESPACE, fingerprint, fingerprint),
            ),
        )
    }

    /**
     * Transaksi TERSENDIRI, dan itu bukan detail: transaksi baris yang gagal sudah di-rollback
     * seluruhnya, jadi menulis pesan kegagalannya di sana sama saja dengan tidak menulisnya.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun failRow(rowId: UUID, reason: String) {
        val row = imports.findRow(rowId) ?: return
        row.markFailed(reason)
        imports.saveRow(row)
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun finishCommit(batchId: UUID) {
        val batch = imports.findBatch(batchId) ?: throw notFound()
        if (batch.status == OrderImportBatchStatus.COMMITTED) return
        val rows = imports.findRows(batchId)
        batch.markCommitted(
            createdRows = rows.count { it.status == OrderImportRowStatus.CREATED },
            failedRows = rows.count { it.status == OrderImportRowStatus.FAILED },
        )
        imports.saveBatch(batch)
    }

    // -------------------------------------------------------------------------------------
    // Berkas contoh
    // -------------------------------------------------------------------------------------

    /**
     * Kecil, tapi ia pembeda antara fitur yang dipakai dan fitur yang tidak: tanpa berkas contoh,
     * operator menebak nama kolom, gagal, dan tak pernah kembali.
     *
     * Pemisahnya ';' dan barisnya CRLF karena itulah yang ditulis DAN dibaca Excel di laptop
     * berlokal Indonesia — berkas contoh yang harus diperbaiki dulu sebelum bisa dipakai adalah
     * kegagalan yang sama.
     */
    override fun template(): String {
        val header = OrderImportColumn.values().joinToString(TEMPLATE_DELIMITER.toString()) { it.label }
        val sample = listOf(
            "Budi Santoso", "081234567890", "budi@contoh.test", "Paket 20 Mbps",
            "Jl. Anggrek No. 12, RT 03/RW 05", "Bekasi", "17111", "Pasang setelah jam 4 sore",
        ).joinToString(TEMPLATE_DELIMITER.toString()) { if (it.contains(TEMPLATE_DELIMITER)) "\"$it\"" else it }
        return header + "\r\n" + sample + "\r\n"
    }

    // -------------------------------------------------------------------------------------
    // Validasi satu baris
    // -------------------------------------------------------------------------------------

    @Suppress("LongMethod", "CyclomaticComplexMethod")
    private fun evaluate(
        tenantId: UUID,
        batchId: UUID,
        columns: OrderImportColumns,
        record: OrderImportCsvRecord,
        seen: MutableSet<String>,
        now: Instant,
    ): OrderImportRow {
        /*
         * Kolom kosong tambahan di ujung dibuang lebih dulu. Excel menuliskan pemisah penutup di
         * setiap baris begitu ada satu sel yang pernah disentuh di kolom sebelahnya; menolak
         * baris karena itu berarti menolak hampir setiap berkas Excel sungguhan.
         */
        val values = record.values.dropTrailingBlanksBeyond(columns.size)
        if (values.size != columns.size) {
            return rejected(
                tenantId, batchId, record.lineNumber, now,
                "Jumlah kolom tidak cocok dengan header (header ${columns.size} kolom, baris ini ${values.size})",
            )
        }

        val name = columns.value(values, OrderImportColumn.NAME)
        val phone = columns.value(values, OrderImportColumn.PHONE)
        val email = columns.value(values, OrderImportColumn.EMAIL)
        val planText = columns.value(values, OrderImportColumn.PLAN)
        val address = columns.value(values, OrderImportColumn.ADDRESS)
        val city = columns.value(values, OrderImportColumn.CITY)
        val postalCode = columns.value(values, OrderImportColumn.POSTAL)
        val notes = columns.value(values, OrderImportColumn.NOTES)

        val problems = mutableListOf<String>()
        OrderImportColumn.values()
            .filter { it.required && columns.value(values, it) == null }
            .forEach { problems += "Kolom '${it.label}' wajib diisi" }

        if (name != null && name.length > MAX_NAME) problems += "Nama maksimal $MAX_NAME karakter"
        if (phone != null) validatePhone(phone)?.let { problems += it }
        if (email != null) validateEmail(email)?.let { problems += it }
        if (city != null && city.length > MAX_CITY) problems += "Nama kota maksimal $MAX_CITY karakter"
        if (postalCode != null && postalCode.length > MAX_POSTAL) {
            problems += "Kode pos maksimal $MAX_POSTAL karakter"
        }
        if (notes != null && notes.length > MAX_NOTES) problems += "Catatan maksimal $MAX_NOTES karakter"

        var plan: PlanCommercialRef? = null
        if (planText != null) {
            plan = resolvePlan(planText)
            if (plan == null) problems += "Paket '$planText' tidak ditemukan atau sudah tidak dijual"
        }

        fun row(status: OrderImportRowStatus, message: String?, fingerprint: String? = null) = OrderImportRow.preview(
            tenantId = tenantId,
            batchId = batchId,
            lineNumber = record.lineNumber,
            status = status,
            message = message,
            fingerprint = fingerprint,
            // Dipotong SEBELUM disimpan: kolom praview lebih sempit dari isi yang mungkin
            // diketik operator, dan baris yang DITOLAK karena kepanjangan tetap harus bisa
            // ditampilkan — bukan meledakkan INSERT seluruh batch.
            name = name?.take(MAX_NAME),
            phone = phone?.take(MAX_PHONE_TEXT),
            email = email?.take(MAX_EMAIL),
            planId = plan?.planId,
            address = address,
            city = city?.take(MAX_CITY),
            postalCode = postalCode?.take(MAX_POSTAL),
            notes = notes?.take(MAX_NOTES),
            now = now,
        )

        if (problems.isNotEmpty()) {
            return row(OrderImportRowStatus.REJECTED, problems.joinToString("; "))
        }

        val fingerprint = fingerprint(
            tenantId, requireNotNull(plan).planId, requireNotNull(name), requireNotNull(phone),
            requireNotNull(address), requireNotNull(city), requireNotNull(postalCode),
        )
        if (!seen.add(fingerprint)) {
            return row(
                OrderImportRowStatus.DUPLICATE,
                "Baris ini kembar dengan baris lain di berkas yang sama (orang, paket, dan alamat sama persis)",
                fingerprint,
            )
        }
        /*
         * Diperiksa SUDAH DI PRAVIEW, bukan hanya saat commit. Operator harus melihat duplikat
         * SEBELUM ia menekan Jalankan — kalau baru ketahuan sesudahnya, angka "500 diterima" yang
         * berubah jadi "48 dibuat" tampak seperti kegagalan sistem, bukan seperti berkas yang
         * memang sudah pernah diimpor.
         */
        if (orders.findOutcome(tenantId, NAMESPACE, fingerprint) != null) {
            return row(
                OrderImportRowStatus.DUPLICATE,
                "Baris ini sudah pernah diimpor pada berkas sebelumnya",
                fingerprint,
            )
        }
        return row(OrderImportRowStatus.ACCEPTED, message = null, fingerprint = fingerprint)
    }

    /**
     * Nomor HP dicocokkan ke aturan yang SAMA dengan `OrderLead.normalizePhone`. Kalau di sini
     * lebih longgar, baris yang lolos praview akan gagal saat commit — dan operator melihat
     * "diterima" berubah jadi "gagal" tanpa pernah diberi tahu apa yang salah.
     */
    private fun validatePhone(phone: String): String? {
        val compact = phone.filterNot { it.isWhitespace() || it == '-' || it == '(' || it == ')' || it == '.' }
        val digits = compact.count { it.isDigit() }
        val shaped = compact.isNotEmpty() &&
            (compact.first().isDigit() || compact.first() == '+') &&
            compact.drop(1).all { it.isDigit() }
        return when {
            digits < MIN_PHONE_DIGITS -> "Nomor HP tidak valid: minimal $MIN_PHONE_DIGITS digit angka"
            digits > MAX_PHONE_DIGITS -> "Nomor HP tidak valid: maksimal $MAX_PHONE_DIGITS digit angka"
            !shaped -> "Nomor HP tidak valid: hanya boleh angka, boleh diawali tanda +"
            else -> null
        }
    }

    private fun validateEmail(email: String): String? = when {
        email.length > MAX_EMAIL -> "Email maksimal $MAX_EMAIL karakter"
        !email.contains('@') || email.startsWith('@') || email.endsWith('@') -> "Email tidak valid: '$email'"
        else -> null
    }

    /**
     * Kolom `paket` boleh berisi UUID paket ATAU NAMA paket (abai huruf besar/kecil). Operator
     * penjualan menyalin dari daftar harga, bukan dari basis data — memaksa UUID berarti fitur
     * ini hanya bisa dipakai orang yang punya akses ke tabelnya.
     *
     * Paket NONAKTIF diperlakukan sama dengan tidak ada: ia masih dipakai langganan lama, tapi
     * menjualnya ke orang baru berarti menjual sesuatu yang sudah ditarik.
     */
    private fun resolvePlan(text: String): PlanCommercialRef? {
        val byId = runCatching { UUID.fromString(text) }.getOrNull()?.let { catalog.findPlanCommercial(it) }
        return (byId ?: catalog.findPlanByName(text))?.takeIf { it.active }
    }

    /**
     * Sidik jari isi baris. Dipakai APA ADANYA sebagai `operation_key` di namespace
     * [NAMESPACE], sehingga penjagaannya dilakukan `order_operation` yang sudah ada — bukan oleh
     * unique index kedua. Dua penjaga untuk satu janji pasti menyimpang diam-diam.
     *
     * Nomor HP direduksi ke DIGIT saja: "0812-3456-7890" dan "081234567890" adalah orang yang
     * sama, dan berkas kedua dari operator yang sama hampir pasti memformatnya berbeda.
     *
     * Ruas dipisah karakter kendali, bukan dirangkai polos: tanpa pemisah, nama "Budi" di kota
     * "Jaya" dan nama "BudiJaya" di kota "" punya sidik jari yang sama.
     */
    private fun fingerprint(
        tenantId: UUID,
        planId: UUID,
        name: String,
        phone: String,
        address: String,
        city: String,
        postalCode: String,
    ): String {
        val canonical = listOf(
            tenantId.toString(), planId.toString(), name.trim(), phone.filter { it.isDigit() },
            address.trim(), city.trim(), postalCode.trim(),
        ).joinToString(FIELD_SEPARATOR)
        return sha256Hex(canonical.toByteArray(StandardCharsets.UTF_8))
    }

    // -------------------------------------------------------------------------------------
    // Pembantu
    // -------------------------------------------------------------------------------------

    private fun requireWithinSizeLimit(byteSize: Long) {
        if (byteSize <= 0) throw ValidationException("Berkas CSV kosong")
        if (byteSize > OrderImportLimits.MAX_FILE_BYTES) {
            throw ValidationException(
                "Ukuran berkas ${byteSize / KIB} KiB melebihi batas " +
                    "${OrderImportLimits.MAX_FILE_BYTES / KIB} KiB. Pastikan yang diunggah berkas CSV, bukan Excel (.xlsx).",
            )
        }
    }

    /** Alamat lengkap prospek, sama bentuknya dengan pintu pemesanan publik. */
    private fun fullAddress(row: OrderImportRow) =
        listOfNotNull(row.address, row.city, row.postalCode).filter { it.isNotBlank() }.joinToString(", ")

    /**
     * Kegagalan diterjemahkan ke kalimat yang bisa dibaca operator penjualan. Exception yang
     * BUKAN kesalahan domain sengaja tidak ditampilkan apa adanya: pesan Hibernate/JDBC tak
     * berguna bagi yang membacanya dan membocorkan bentuk skema ke layar.
     */
    private fun readable(ex: Throwable): String = when (ex) {
        is ValidationException, is ConflictException, is NotFoundException ->
            ex.message ?: "Baris ini ditolak sistem"
        else -> "Gagal membuat pesanan untuk baris ini. Coba jalankan ulang; bila tetap gagal, hubungi admin."
    }

    private fun rejected(tenantId: UUID, batchId: UUID, lineNumber: Int, now: Instant, message: String) =
        OrderImportRow.preview(
            tenantId = tenantId,
            batchId = batchId,
            lineNumber = lineNumber,
            status = OrderImportRowStatus.REJECTED,
            message = message,
            now = now,
        )

    private fun notFound() = NotFoundException("Impor tidak ditemukan")

    private fun detail(batch: OrderImportBatch) = detail(batch, imports.findRows(batch.id))

    private fun detail(batch: OrderImportBatch, rows: List<OrderImportRow>) = OrderImportBatchDetailView(
        batch = batch.toSummary(),
        rows = rows.sortedBy { it.lineNumber }.map { it.toView() },
    )

    private fun OrderImportBatch.toSummary() = OrderImportBatchSummaryView(
        id = id,
        fileName = fileName,
        status = OrderImportBatchStatusView.valueOf(status.name),
        delimiter = delimiter.toString(),
        byteSize = byteSize,
        totalRows = totalRows,
        acceptedRows = acceptedRows,
        rejectedRows = rejectedRows,
        createdRows = createdRows,
        failedRows = failedRows,
        importedBy = importedBy,
        createdAt = createdAt,
        committedAt = committedAt,
    )

    private fun OrderImportRow.toView() = OrderImportRowView(
        id = id,
        lineNumber = lineNumber,
        status = OrderImportRowStatusView.valueOf(status.name),
        message = message,
        name = name,
        phone = phone,
        email = email,
        planId = planId,
        address = address,
        city = city,
        postalCode = postalCode,
        notes = notes,
        orderId = orderId,
        orderNumber = orderNumber,
    )

    private fun List<String>.dropTrailingBlanksBeyond(limit: Int): List<String> {
        var end = size
        while (end > limit && this[end - 1].isBlank()) end--
        return if (end == size) this else subList(0, end)
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private companion object {
        /**
         * Namespace operation key impor. Terpisah dari `public-order` supaya pesanan yang masuk
         * lewat berkas tak pernah bisa "membalas" pesanan yang masuk lewat formulir publik hanya
         * karena kuncinya kebetulan sama.
         */
        const val NAMESPACE = "order-import"
        const val FIELD_SEPARATOR = ""
        const val TEMPLATE_DELIMITER = ';'
        const val KIB = 1024
        const val MAX_NAME = 150
        const val MAX_PHONE_TEXT = 32
        const val MAX_EMAIL = 200
        const val MAX_CITY = 120
        const val MAX_POSTAL = 24
        const val MAX_NOTES = 1000
        const val MIN_PHONE_DIGITS = 8
        const val MAX_PHONE_DIGITS = 15
    }
}

/**
 * Kolom yang dikenali, beserta padanannya. Alias Indonesia DAN Inggris sama-sama diterima:
 * berkas yang beredar di lapangan lahir dari dua sumber — templat yang kami terbitkan (Indonesia)
 * dan ekspor dari sistem lama/Excel milik ISP (sering Inggris) — dan memaksa salah satunya
 * berarti setiap impor didahului pekerjaan mengganti nama kolom secara manual.
 *
 * [label] adalah nama yang muncul di pesan penolakan; ia SENGAJA berbahasa Indonesia karena yang
 * membacanya operator penjualan.
 */
private enum class OrderImportColumn(val label: String, val required: Boolean, val aliases: Set<String>) {
    NAME("nama", true, setOf("nama", "name", "nama_pelanggan", "nama_calon_pelanggan", "customer_name")),
    PHONE("hp", true, setOf("hp", "no_hp", "nohp", "nomor_hp", "phone", "telepon", "telp", "wa")),
    EMAIL("email", false, setOf("email", "surel", "e_mail")),
    PLAN("paket", true, setOf("paket", "nama_paket", "plan", "plan_id", "paket_id", "package", "package_name")),
    ADDRESS("alamat", true, setOf("alamat", "address", "alamat_pemasangan")),
    CITY("kota", true, setOf("kota", "city", "kabupaten", "kota_kabupaten")),
    POSTAL("kodepos", true, setOf("kodepos", "kode_pos", "postal_code", "postalcode", "zip", "zip_code")),
    NOTES("catatan", false, setOf("catatan", "notes", "note", "keterangan")),
}

/**
 * Pemetaan header → indeks kolom. Pencocokan abai huruf besar/kecil dan abai spasi/titik/hubung
 * ("Kode Pos", "kode-pos", dan "KODEPOS" adalah kolom yang sama), karena nama kolom diketik
 * manusia di Excel dan tak pernah konsisten.
 */
private class OrderImportColumns(val size: Int, private val index: Map<OrderImportColumn, Int>) {

    fun value(values: List<String>, column: OrderImportColumn): String? =
        index[column]?.let { values.getOrNull(it)?.trim()?.ifBlank { null } }

    companion object {
        private val SEPARATORS = Regex("[\\s._-]+")

        fun of(header: List<String>): OrderImportColumns {
            val index = mutableMapOf<OrderImportColumn, Int>()
            header.forEachIndexed { position, cell ->
                val normalized = cell.trim().lowercase().replace(SEPARATORS, "_").trim('_')
                // Kolom tak dikenal DIABAIKAN, bukan ditolak. Ekspor sistem lama selalu membawa
                // kolom tambahan (id internal, tanggal, status) yang tak ada urusannya dengan
                // pesanan, dan menolak berkasnya karena itu tak menolong siapa pun.
                val column = OrderImportColumn.values().firstOrNull { normalized in it.aliases } ?: return@forEachIndexed
                index.putIfAbsent(column, position)
            }
            val missing = OrderImportColumn.values().filter { it.required && !index.containsKey(it) }
            if (missing.isNotEmpty()) {
                throw ValidationException(
                    "Kolom wajib belum ada di baris pertama berkas: " +
                        missing.joinToString(", ") { "'${it.label}'" } +
                        ". Unduh berkas contoh lewat menu impor lalu isi ulang.",
                )
            }
            return OrderImportColumns(header.size, index)
        }
    }
}

package com.duluin.ftth.order

import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.iam.application.port.inbound.OnboardTenantCommand
import com.duluin.ftth.iam.application.port.inbound.OnboardTenantUseCase
import com.jayway.jsonpath.JsonPath
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.nio.charset.StandardCharsets
import java.util.UUID

/**
 * Impor massal pesanan dari CSV (Bagian 3).
 *
 * Yang dibuktikan di sini tak satu pun bisa dibuktikan unit test, karena semuanya adalah janji
 * tentang apa yang TERSISA DI DATABASE setelah sesuatu berjalan atau gagal di tengah:
 *
 * 1. Berkas berbentuk Excel Indonesia (BOM UTF-8, pemisah ';', akhir baris CRLF) benar-benar
 *    sampai jadi pesanan. Inilah bentuk berkas yang SUNGGUHAN dipakai operator; parser yang
 *    hanya menerima koma+LF akan lulus semua unit test dan gagal di setiap unggahan nyata.
 * 2. Praview TIDAK MENULIS APA PUN selain hasil urainya. Ini seluruh alasan fitur dua langkah
 *    ada; kalau ia bocor satu pesanan saja, janjinya batal.
 * 3. Baris rusak ditolak dengan KALIMAT yang bisa dibaca operator, dan baris baik di berkas yang
 *    sama tetap lolos.
 * 4. Satu baris yang gagal saat commit tidak menggagalkan baris lain. Ini menuntut transaksi
 *    per baris yang SUNGGUHAN (`REQUIRES_NEW` lewat proxy); rollback-only yang menjalar hanya
 *    terlihat lewat test seperti ini.
 * 5. Unggah ulang berkas yang sama tidak melahirkan pesanan kembar — baik lewat batch yang sama
 *    maupun lewat penjagaan tingkat baris saat isinya ditambahi.
 * 6. Berkas kelewat besar ditolak dengan satu kalimat dan TIDAK meninggalkan jejak apa pun.
 * 7. Jejak audit menyimpan siapa yang mengimpor dan berkas apa.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Suppress("LargeClass")
class OrderImportIT {

    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var onboarding: OnboardTenantUseCase
    @Autowired private lateinit var txManager: PlatformTransactionManager

    @PersistenceContext private lateinit var em: EntityManager

    private val pass = "secret12345"

    private fun uniq() = UUID.randomUUID().toString().replace("-", "").substring(0, 8)
    private fun phone() = "0812" + (1..8).joinToString("") { (0..9).random().toString() }

    private data class Tenant(val id: UUID, val slug: String, val token: String, val userId: UUID)

    private fun login(slug: String, email: String): String {
        val json = mockMvc.perform(
            post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("""{"tenantSlug":"$slug","email":"$email","password":"$pass"}"""),
        ).andExpect(status().isOk).andReturn().response.contentAsString
        return JsonPath.read(json, "$.accessToken")
    }

    private fun newTenantAdmin(prefix: String): Tenant {
        val slug = "$prefix${uniq()}"
        val admin = "admin@$slug.test"
        val tenantId = onboarding.onboard(OnboardTenantCommand(slug, "Tenant $slug", admin, "Admin", pass)).tenant.id
        val token = login(slug, admin)
        // Id pengguna diambil dari `/api/me`, bukan ditebak: jejak audit impor harus menunjuk
        // pengguna yang SUNGGUHAN memegang token, dan itulah satu-satunya sumber yang jujur.
        val userId = UUID.fromString(JsonPath.read<String>(get("/api/me", token), "$.id"))
        return Tenant(tenantId, slug, token, userId)
    }

    private fun get(url: String, token: String, expected: Int = 200): String =
        mockMvc.perform(get(url).header("Authorization", "Bearer $token"))
            .andExpect { assertThat(it.response.status).isEqualTo(expected) }
            .andReturn().response.contentAsString

    private fun postJson(url: String, token: String, body: String, expected: Int = 201): String =
        mockMvc.perform(
            post(url).header("Authorization", "Bearer $token")
                .contentType(MediaType.APPLICATION_JSON).content(body),
        ).andExpect { assertThat(it.response.status).isEqualTo(expected) }
            .andReturn().response.contentAsString

    private fun postEmpty(url: String, token: String, expected: Int = 200): String =
        mockMvc.perform(post(url).header("Authorization", "Bearer $token"))
            .andExpect { assertThat(it.response.status).isEqualTo(expected) }
            .andReturn().response.contentAsString

    private data class Plan(val id: String, val name: String)

    private fun plan(tenant: Tenant, label: String): Plan {
        val name = "$label ${uniq()}"
        val id = JsonPath.read<String>(
            postJson(
                "/api/catalog/plans", tenant.token,
                """{"name":"$name","description":null,"price":150000,
                    "downMbps":20,"upMbps":10,"serviceTypes":["PPPOE"]}""",
            ),
            "$.id",
        )
        return Plan(id, name)
    }

    /** Menarik paket dari penjualan — persis yang terjadi di antara praview dan commit. */
    private fun deactivate(tenant: Tenant, plan: Plan) {
        mockMvc.perform(
            put("/api/catalog/plans/${plan.id}").header("Authorization", "Bearer ${tenant.token}")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """{"name":"${plan.name}","description":null,"price":150000,
                        "downMbps":20,"upMbps":10,"serviceTypes":["PPPOE"],"active":false}""",
                ),
        ).andExpect(status().isOk)
    }

    /**
     * Unggahan multipart. Nama bagian "file" dan `originalFilename` SENGAJA dibuat nyata: nama
     * berkas adalah kolom audit yang dijanjikan fitur ini, dan bagian yang salah nama hanya
     * akan tampak sebagai 400 tanpa penjelasan.
     */
    private fun upload(
        tenant: Tenant,
        fileName: String,
        content: ByteArray,
        expected: Int = 200,
    ): String {
        val part = MockMultipartFile("file", fileName, "text/csv", content)
        return mockMvc.perform(
            multipart("/api/orders/import/preview").file(part)
                .header("Authorization", "Bearer ${tenant.token}"),
        ).andExpect { assertThat(it.response.status).isEqualTo(expected) }
            .andReturn().response.contentAsString
    }

    /**
     * Berkas seperti yang DITULIS Excel berlokal Indonesia: BOM UTF-8 di depan, pemisah titik
     * koma, akhir baris CRLF. Ketiganya bersama-sama, karena ketiganya memang selalu datang
     * bersama-sama dari "Save As → CSV" di Windows.
     */
    private fun excelCsv(vararg lines: String): ByteArray =
        ("\uFEFF" + lines.joinToString("\r\n") + "\r\n").toByteArray(StandardCharsets.UTF_8)

    @Suppress("UNCHECKED_CAST")
    private fun <T> asTenant(tenantId: UUID, block: () -> T): T =
        TenantContext.runAs(tenantId) { TransactionTemplate(txManager).execute { block() } as T }

    /**
     * Query mentah dijalankan di dalam konteks tenant DAN transaksi tersendiri. Keduanya wajib:
     * tanpa `TenantContext`, GUC `app.tenant_id` tak terpasang dan setiap tabel ber-RLS FORCE
     * memulangkan NOL baris tanpa error — assertion "tak ada baris" akan lulus karena alasan
     * yang sama sekali salah.
     */
    private fun countRows(tenantId: UUID, sql: String, params: Map<String, Any> = emptyMap()): Long =
        asTenant(tenantId) {
            val query = em.createNativeQuery(sql)
            params.forEach { (name, value) -> query.setParameter(name, value) }
            (query.singleResult as Number).toLong()
        }

    private fun orderCount(tenantId: UUID) =
        countRows(tenantId, "SELECT count(*) FROM order_record WHERE tenant_id = :t", mapOf("t" to tenantId))

    private fun importedLeadCount(tenantId: UUID) = countRows(
        tenantId,
        "SELECT count(*) FROM order_lead WHERE tenant_id = :t AND source = 'CSV_IMPORT'",
        mapOf("t" to tenantId),
    )

    private fun header() = "nama;hp;email;paket;alamat;kota;kodepos;catatan"

    // =======================================================================================
    // 1 + 7
    // =======================================================================================

    @Test
    fun `an excel shaped csv becomes real submitted orders and records who imported it`() {
        val tenant = newTenantAdmin("imp")
        val paket = plan(tenant, "Paket Impor")
        val satu = phone()
        val dua = phone()

        val csv = excelCsv(
            header(),
            // Catatan sengaja memuat ';' DI DALAM tanda kutip: kalau parser memecah baris tanpa
            // menghormati kutip, baris ini berubah jadi 9 kolom dan ditolak.
            """Budi Santoso;$satu;budi@contoh.test;${paket.name};Jl. Anggrek No. 12;Bekasi;17111;"Pagi; sore juga boleh"""",
            "Siti Aminah;$dua;;${paket.name};Jl. Melati No. 7;Bekasi;17112;",
        )

        val preview = upload(tenant, "pesanan-agustus.csv", csv)
        val batchId = JsonPath.read<String>(preview, "$.batch.id")
        assertThat(JsonPath.read<String>(preview, "$.batch.status")).isEqualTo("PREVIEWED")
        assertThat(JsonPath.read<Int>(preview, "$.batch.totalRows")).isEqualTo(2)
        assertThat(JsonPath.read<Int>(preview, "$.batch.acceptedRows")).isEqualTo(2)
        // Pemisah yang benar-benar terdeteksi ikut dicatat; tanpa ini keluhan "semua baris saya
        // ditolak" hanya bisa dijawab dengan menebak.
        assertThat(JsonPath.read<String>(preview, "$.batch.delimiter")).isEqualTo(";")
        assertThat(JsonPath.read<List<String>>(preview, "$.rows[*].status")).containsOnly("ACCEPTED")
        // Nomor baris mengikuti BERKAS ASLI (header = 1), karena di situlah operator memperbaikinya.
        assertThat(JsonPath.read<List<Int>>(preview, "$.rows[*].lineNumber")).containsExactly(2, 3)
        assertThat(JsonPath.read<String>(preview, "$.rows[0].notes")).isEqualTo("Pagi; sore juga boleh")
        // BOM harus terkupas: tanpa itu kolom pertama bernama "﻿nama" dan tak pernah cocok.
        assertThat(JsonPath.read<String>(preview, "$.rows[0].name")).isEqualTo("Budi Santoso")

        val committed = postEmpty("/api/orders/import/$batchId/commit", tenant.token)
        assertThat(JsonPath.read<String>(committed, "$.batch.status")).isEqualTo("COMMITTED")
        assertThat(JsonPath.read<Int>(committed, "$.batch.createdRows")).isEqualTo(2)
        assertThat(JsonPath.read<Int>(committed, "$.batch.failedRows")).isEqualTo(0)
        assertThat(JsonPath.read<List<String>>(committed, "$.rows[*].status")).containsOnly("CREATED")
        assertThat(JsonPath.read<List<String>>(committed, "$.rows[*].orderNumber"))
            .allSatisfy { assertThat(it).matches("ORD-\\d{4}-\\d{4}") }

        assertThat(orderCount(tenant.id)).isEqualTo(2)
        assertThat(importedLeadCount(tenant.id)).isEqualTo(2)
        /*
         * SUBMITTED, bukan DRAFT. Pesanan yang berhenti di DRAFT tak punya padanan status portal,
         * jadi nomor yang diterima pelanggan selamanya menjawab "tidak ditemukan".
         */
        assertThat(
            countRows(
                tenant.id,
                "SELECT count(*) FROM order_record WHERE tenant_id = :t AND status = 'SUBMITTED'",
                mapOf("t" to tenant.id),
            ),
        ).isEqualTo(2)

        // --- 7: jejak audit -----------------------------------------------------------------
        val history = get("/api/orders/import?limit=10", tenant.token)
        assertThat(JsonPath.read<List<String>>(history, "$[*].id")).contains(batchId)
        assertThat(JsonPath.read<String>(history, "$[0].fileName")).isEqualTo("pesanan-agustus.csv")
        assertThat(JsonPath.read<String>(history, "$[0].importedBy")).isEqualTo(tenant.userId.toString())
        assertThat(JsonPath.read<Int>(history, "$[0].createdRows")).isEqualTo(2)
        assertThat(
            countRows(
                tenant.id,
                "SELECT count(*) FROM order_import_batch WHERE tenant_id = :t " +
                    "AND file_name = 'pesanan-agustus.csv' AND imported_by = :u AND committed_at IS NOT NULL",
                mapOf("t" to tenant.id, "u" to tenant.userId),
            ),
        ).isEqualTo(1)

        // Membaca ulang praview harus memulangkan batch yang sama apa adanya — operator yang
        // me-refresh halaman tidak boleh melihat impor kedua.
        val reread = get("/api/orders/import/$batchId", tenant.token)
        assertThat(JsonPath.read<String>(reread, "$.batch.id")).isEqualTo(batchId)
        assertThat(JsonPath.read<Int>(reread, "$.batch.createdRows")).isEqualTo(2)
    }

    // =======================================================================================
    // 2
    // =======================================================================================

    @Test
    fun `a preview writes nothing except its own parse result`() {
        val tenant = newTenantAdmin("imppre")
        val paket = plan(tenant, "Paket Praview")

        val csv = excelCsv(
            header(),
            "Budi Santoso;${phone()};;${paket.name};Jl. Anggrek No. 12;Bekasi;17111;",
            "Siti Aminah;${phone()};;${paket.name};Jl. Melati No. 7;Bekasi;17112;",
            "Joko Widodo;${phone()};;${paket.name};Jl. Kenanga No. 3;Bekasi;17113;",
        )
        val preview = upload(tenant, "calon.csv", csv)
        assertThat(JsonPath.read<Int>(preview, "$.batch.acceptedRows")).isEqualTo(3)

        /*
         * INI seluruh alasan fitur dua langkah ada. Impor 500 baris yang menulis separuh lalu
         * mati di tengah tak punya tombol undo; praview yang bocor satu pesanan saja sudah
         * membatalkan janjinya.
         */
        assertThat(orderCount(tenant.id)).isZero()
        assertThat(importedLeadCount(tenant.id)).isZero()
        assertThat(
            countRows(
                tenant.id, "SELECT count(*) FROM order_import_row WHERE tenant_id = :t AND status = 'ACCEPTED'",
                mapOf("t" to tenant.id),
            ),
        ).isEqualTo(3)
    }

    // =======================================================================================
    // 3
    // =======================================================================================

    @Test
    fun `broken rows are rejected with readable indonesian reasons while good rows survive`() {
        val tenant = newTenantAdmin("impbad")
        val paket = plan(tenant, "Paket Campur")

        val csv = excelCsv(
            header(),
            "Budi Santoso;${phone()};;${paket.name};Jl. Anggrek No. 12;Bekasi;17111;",
            // HP bukan nomor sama sekali.
            "Siti Aminah;bukan-nomor;;${paket.name};Jl. Melati No. 7;Bekasi;17112;",
            // Nama kosong — kolom wajib.
            ";${phone()};;${paket.name};Jl. Kenanga No. 3;Bekasi;17113;",
            // Paket yang tak pernah ada di katalog.
            "Joko Widodo;${phone()};;Paket Ngawur;Jl. Mawar No. 9;Bekasi;17114;",
        )

        val preview = upload(tenant, "campur.csv", csv)
        assertThat(JsonPath.read<Int>(preview, "$.batch.totalRows")).isEqualTo(4)
        assertThat(JsonPath.read<Int>(preview, "$.batch.acceptedRows")).isEqualTo(1)
        assertThat(JsonPath.read<Int>(preview, "$.batch.rejectedRows")).isEqualTo(3)

        val statuses = JsonPath.read<List<String>>(preview, "$.rows[*].status")
        assertThat(statuses).containsExactly("ACCEPTED", "REJECTED", "REJECTED", "REJECTED")

        /*
         * Dibaca satu per satu, BUKAN lewat `$.rows[*].message`: baris ACCEPTED punya message
         * null, dan JsonPath memperlakukan null di jalur wildcard dengan cara yang membuat
         * indeksnya bergeser diam-diam — assertion akan lulus/gagal karena hal yang salah.
         */
        val messages = (1..3).map { JsonPath.read<String>(preview, "$.rows[$it].message") }
        // Kalimatnya dibaca operator penjualan, bukan pengembang: tak boleh ada nama kelas,
        // nama kolom database, atau jejak stack.
        assertThat(messages[0]).contains("Nomor HP tidak valid")
        assertThat(messages[1]).contains("wajib diisi").contains("nama")
        assertThat(messages[2]).contains("Paket 'Paket Ngawur'")
        assertThat(messages).allSatisfy { assertThat(it).doesNotContain("Exception") }

        // Baris rusak tidak boleh menahan baris baik: yang satu itu tetap jadi pesanan.
        val batchId = JsonPath.read<String>(preview, "$.batch.id")
        val committed = postEmpty("/api/orders/import/$batchId/commit", tenant.token)
        assertThat(JsonPath.read<Int>(committed, "$.batch.createdRows")).isEqualTo(1)
        assertThat(orderCount(tenant.id)).isEqualTo(1)
    }

    // =======================================================================================
    // 4
    // =======================================================================================

    @Test
    fun `one row failing at commit time does not take the rest of the file down with it`() {
        val tenant = newTenantAdmin("impiso")
        val tetap = plan(tenant, "Paket Tetap")
        val ditarik = plan(tenant, "Paket Ditarik")

        val csv = excelCsv(
            header(),
            "Budi Santoso;${phone()};;${tetap.name};Jl. Anggrek No. 12;Bekasi;17111;",
            "Siti Aminah;${phone()};;${ditarik.name};Jl. Melati No. 7;Bekasi;17112;",
            "Joko Widodo;${phone()};;${tetap.name};Jl. Kenanga No. 3;Bekasi;17113;",
        )
        val preview = upload(tenant, "isolasi.csv", csv)
        val batchId = JsonPath.read<String>(preview, "$.batch.id")
        assertThat(JsonPath.read<Int>(preview, "$.batch.acceptedRows")).isEqualTo(3)

        /*
         * Paket ditarik SETELAH praview dibuat. Ini bukan kasus tepi: praview bisa dibuka kemarin
         * sore dan disetujui atasan pagi ini. Baris yang menunjuknya HARUS gagal sendirian —
         * kalau transaksi per barisnya palsu (dipanggil lewat `this`, atau pemanggilnya ikut
         * transaksional), rollback-only menjalar dan KETIGA baris ikut mati.
         */
        deactivate(tenant, ditarik)

        val committed = postEmpty("/api/orders/import/$batchId/commit", tenant.token)
        assertThat(JsonPath.read<Int>(committed, "$.batch.createdRows")).isEqualTo(2)
        assertThat(JsonPath.read<Int>(committed, "$.batch.failedRows")).isEqualTo(1)
        assertThat(JsonPath.read<List<String>>(committed, "$.rows[*].status"))
            .containsExactly("CREATED", "FAILED", "CREATED")
        assertThat(JsonPath.read<String>(committed, "$.rows[1].message")).contains("sudah tidak dijual")

        // Dua pesanan benar-benar ada di DATABASE, bukan hanya di badan respons.
        assertThat(orderCount(tenant.id)).isEqualTo(2)
        assertThat(importedLeadCount(tenant.id)).isEqualTo(2)
    }

    // =======================================================================================
    // 5
    // =======================================================================================

    @Test
    fun `re-uploading the same file returns the same batch and never duplicates orders`() {
        val tenant = newTenantAdmin("impdup")
        val paket = plan(tenant, "Paket Ulang")
        val satu = phone()
        val dua = phone()
        val barisSatu = "Budi Santoso;$satu;;${paket.name};Jl. Anggrek No. 12;Bekasi;17111;"
        val barisDua = "Siti Aminah;$dua;;${paket.name};Jl. Melati No. 7;Bekasi;17112;"

        val csv = excelCsv(header(), barisSatu, barisDua)
        val first = upload(tenant, "ulang.csv", csv)
        val batchId = JsonPath.read<String>(first, "$.batch.id")

        // Operator yang ragu apakah unggahannya tadi berhasil PASTI mengunggah lagi. Yang ia
        // terima harus praview yang sama, bukan praview kedua — apalagi galat.
        val second = upload(tenant, "ulang (1).csv", csv)
        assertThat(JsonPath.read<String>(second, "$.batch.id")).isEqualTo(batchId)
        assertThat(
            countRows(
                tenant.id, "SELECT count(*) FROM order_import_batch WHERE tenant_id = :t",
                mapOf("t" to tenant.id),
            ),
        ).isEqualTo(1)

        postEmpty("/api/orders/import/$batchId/commit", tenant.token)
        assertThat(orderCount(tenant.id)).isEqualTo(2)

        // Menekan "Jalankan" dua kali (respons pertama terasa lambat) adalah perilaku normal;
        // ia harus memulangkan hasil yang sama, bukan 409 dan bukan 2 pesanan tambahan.
        val replay = postEmpty("/api/orders/import/$batchId/commit", tenant.token)
        assertThat(JsonPath.read<Int>(replay, "$.batch.createdRows")).isEqualTo(2)
        assertThat(orderCount(tenant.id)).isEqualTo(2)

        /*
         * Kasus yang paling sering terjadi di lapangan: berkas yang SAMA ditambahi beberapa baris
         * baru di bawahnya lalu diunggah ulang. Hash berkasnya berbeda, jadi penjaga tingkat
         * berkas tak menolong sama sekali — yang harus bekerja adalah sidik jari per baris.
         */
        val grown = excelCsv(header(), barisSatu, barisDua, "Joko Widodo;${phone()};;${paket.name};Jl. Kenanga No. 3;Bekasi;17113;")
        val third = upload(tenant, "ulang-tambah.csv", grown)
        val thirdId = JsonPath.read<String>(third, "$.batch.id")
        assertThat(thirdId).isNotEqualTo(batchId)
        assertThat(JsonPath.read<List<String>>(third, "$.rows[*].status"))
            .containsExactly("DUPLICATE", "DUPLICATE", "ACCEPTED")
        assertThat(JsonPath.read<String>(third, "$.rows[0].message")).contains("sudah pernah diimpor")

        postEmpty("/api/orders/import/$thirdId/commit", tenant.token)
        assertThat(orderCount(tenant.id)).isEqualTo(3)
        assertThat(importedLeadCount(tenant.id)).isEqualTo(3)
    }

    // =======================================================================================
    // 6
    // =======================================================================================

    @Test
    fun `an oversized file is refused with one sentence and leaves nothing behind`() {
        val tenant = newTenantAdmin("impbig")
        val paket = plan(tenant, "Paket Besar")

        // Sedikit di atas 2 MiB — berkas .xlsx yang salah pilih berukuran persis di wilayah ini.
        val filler = "Budi Santoso;${phone()};;${paket.name};Jl. Anggrek No. 12;Bekasi;17111;\r\n"
        val body = StringBuilder("\uFEFF" + header() + "\r\n")
        while (body.length < 2 * 1024 * 1024 + 1024) body.append(filler)

        val response = upload(tenant, "kegedean.csv", body.toString().toByteArray(StandardCharsets.UTF_8), expected = 400)
        // Satu KALIMAT, bukan jejak stack — dan bukan OOM yang menjatuhkan proses untuk semua
        // tenant lain, yang justru akan tampak sebagai test ini menggantung.
        assertThat(JsonPath.read<String>(response, "$.detail")).contains("melebihi batas")

        assertThat(
            countRows(
                tenant.id, "SELECT count(*) FROM order_import_batch WHERE tenant_id = :t",
                mapOf("t" to tenant.id),
            ),
        ).isZero()
        assertThat(
            countRows(
                tenant.id, "SELECT count(*) FROM order_import_row WHERE tenant_id = :t",
                mapOf("t" to tenant.id),
            ),
        ).isZero()
        assertThat(orderCount(tenant.id)).isZero()
    }

    // =======================================================================================
    // Berkas contoh
    // =======================================================================================

    @Test
    fun `the template is a csv file the operator can open straight in excel`() {
        val tenant = newTenantAdmin("imptpl")
        val response = mockMvc.perform(
            get("/api/orders/import/template").header("Authorization", "Bearer ${tenant.token}"),
        ).andExpect(status().isOk).andReturn().response

        assertThat(response.contentType).startsWith("text/csv")
        val body = String(response.contentAsByteArray, StandardCharsets.UTF_8)
        assertThat(body).startsWith("\uFEFF")
        assertThat(body).contains("nama;hp;email;paket;alamat;kota;kodepos;catatan")

        // Bukti paling jujur bahwa berkas contohnya benar: ia sendiri harus bisa diurai fitur
        // ini tanpa satu pun perbaikan manual.
        val preview = upload(tenant, "contoh.csv", response.contentAsByteArray)
        assertThat(JsonPath.read<Int>(preview, "$.batch.totalRows")).isEqualTo(1)
        // Baris teladannya merujuk paket yang belum tentu ada di tenant ini — jadi ia DITOLAK,
        // bukan diterima. Yang dibuktikan di sini adalah bentuk berkasnya terbaca utuh.
        assertThat(JsonPath.read<List<Int>>(preview, "$.rows[*].lineNumber")).containsExactly(2)
        assertThat(JsonPath.read<String>(preview, "$.rows[0].name")).isEqualTo("Budi Santoso")
    }
}

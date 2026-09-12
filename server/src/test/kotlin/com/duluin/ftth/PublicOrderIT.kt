package com.duluin.ftth

import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.iam.application.port.inbound.OnboardTenantCommand
import com.duluin.ftth.iam.application.port.inbound.OnboardTenantUseCase
import com.duluin.ftth.order.OrderApi
import com.duluin.ftth.order.OrderFulfillmentCommand
import com.duluin.ftth.order.OrderTransition
import com.jayway.jsonpath.JsonPath
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID

/**
 * Pintu pemesanan PUBLIK (P4.4, P4.5, P4.7) dan lanjutannya ke lapangan (P5.4, P5.6).
 *
 * Yang dijaga di sini tak bisa dibuktikan unit test mana pun, karena semuanya tentang apa yang
 * terjadi di luar batas aplikasi:
 * 1. Pengunjung TANPA token bisa memesan. Jalur ini menembus filter keamanan, resolusi tenant
 *    dari slug, dan `TenantContext` yang harus terpasang SEBELUM session Hibernate dibuka —
 *    kalau salah satu meleset, query ber-RLS memulangkan nol baris TANPA error apa pun.
 * 2. Pelacakan tidak pernah menyeberang tenant dan tidak pernah membocorkan apa pun selain
 *    ringkasan status.
 * 3. Rem laju benar-benar menahan, dan hitungannya ada di DATABASE — bukan di memori proses.
 * 4. Menerima pesanan membuka work order PSB yang tertaut balik ke pesanannya, sekali saja.
 * 5. Persetujuan work order membawa efek `ORDER`, dan efek itu menutup pesanan si pengunjung.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Suppress("LargeClass")
class PublicOrderIT {

    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var onboarding: OnboardTenantUseCase
    @Autowired private lateinit var txManager: PlatformTransactionManager
    @Autowired private lateinit var orders: OrderApi

    @PersistenceContext private lateinit var em: EntityManager

    private val pass = "secret12345"
    private fun uniq() = UUID.randomUUID().toString().replace("-", "").substring(0, 8)

    private data class Tenant(val id: UUID, val slug: String, val token: String)

    /**
     * Alamat IP acak untuk SETIAP pemakaian.
     *
     * `public_request_throttle` sengaja tidak ber-tenant dan tidak pernah dikosongkan di antara
     * test — ia memang harus bertahan lintas permintaan. Konsekuensinya: IP tetap seperti
     * "127.0.0.1" akan membawa cacah dari test lain DAN dari eksekusi suite sebelumnya dalam
     * jam yang sama, sehingga test yang lulus sekali akan gagal saat diulang. IP acak
     * memisahkan ember tiap test tanpa perlu menyentuh tabelnya.
     */
    private fun ip() = "198.51.${(0..255).random()}.${(1..254).random()}"

    /** Nomor HP acak tapi tetap berbentuk nomor sungguhan — domain menolak yang bukan angka. */
    private fun phone() = "0812" + (1..8).joinToString("") { (0..9).random().toString() }

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
        return Tenant(tenantId, slug, login(slug, admin))
    }

    private fun post(url: String, token: String, body: String, expected: Int = 201): String =
        mockMvc.perform(
            post(url).header("Authorization", "Bearer $token")
                .contentType(MediaType.APPLICATION_JSON).content(body),
        ).andExpect { assertThat(it.response.status).isEqualTo(expected) }
            .andReturn().response.contentAsString

    private fun get(url: String, token: String, expected: Int = 200): String =
        mockMvc.perform(get(url).header("Authorization", "Bearer $token"))
            .andExpect { assertThat(it.response.status).isEqualTo(expected) }
            .andReturn().response.contentAsString

    // ---------------------------------------------------------------------------------------
    // Pintu publik: tanpa header Authorization sama sekali. Kalau salah satu endpoint ini
    // menuntut autentikasi, test-test di bawah gagal dengan 401 — itulah gunanya tak pernah
    // menyisipkan token di sini.
    // ---------------------------------------------------------------------------------------

    private fun publicPost(url: String, body: String, ip: String, expected: Int = 200): String =
        mockMvc.perform(
            post(url).contentType(MediaType.APPLICATION_JSON).content(body)
                .with { it.remoteAddr = ip; it },
        ).andExpect { assertThat(it.response.status).isEqualTo(expected) }
            .andReturn().response.contentAsString

    private fun publicPostStatus(url: String, body: String, ip: String): Int =
        mockMvc.perform(
            post(url).contentType(MediaType.APPLICATION_JSON).content(body)
                .with { it.remoteAddr = ip; it },
        ).andReturn().response.status

    private fun publicGet(url: String, ip: String, expected: Int = 200): String =
        mockMvc.perform(get(url).with { it.remoteAddr = ip; it })
            .andExpect { assertThat(it.response.status).isEqualTo(expected) }
            .andReturn().response.contentAsString

    /** Paket katalog yang aktif; formulir publik hanya boleh memilih yang ini. */
    private fun plan(tenant: Tenant, name: String = "Paket Publik"): String =
        JsonPath.read(
            post(
                "/api/catalog/plans", tenant.token,
                """{"name":"$name ${uniq()}","description":null,"price":150000,
                    "downMbps":20,"upMbps":10,"serviceTypes":["PPPOE"]}""",
            ),
            "$.id",
        )

    private fun submission(planId: String, phone: String, name: String = "Pengunjung Uji", extra: String = "") =
        """{"name":"$name","phone":"$phone","planId":"$planId","address":"$STREET",
            "city":"Bekasi","postalCode":"17111"$extra}"""

    /**
     * Query mentah dijalankan di dalam konteks tenant DAN transaksi tersendiri. Keduanya wajib:
     * tanpa `TenantContext` GUC `app.tenant_id` tak terpasang dan setiap tabel ber-RLS FORCE
     * memulangkan nol baris TANPA error — assertion "tak ada baris" akan lulus karena alasan
     * yang sama sekali salah.
     */
    @Suppress("UNCHECKED_CAST")
    private fun <T> asTenant(tenantId: UUID, block: () -> T): T =
        TenantContext.runAs(tenantId) { TransactionTemplate(txManager).execute { block() } as T }

    private fun countRows(tenantId: UUID, sql: String, params: Map<String, Any>): Long = asTenant(tenantId) {
        val query = em.createNativeQuery(sql)
        params.forEach { (name, value) -> query.setParameter(name, value) }
        (query.singleResult as Number).toLong()
    }

    @Test
    fun `an anonymous visitor orders without an account and then tracks it`() {
        val tenant = newTenantAdmin("pub")
        val planId = plan(tenant)
        val visitorIp = ip()
        val phone = phone()

        // Formulirnya harus bisa menampilkan pilihan paket tanpa token — tanpa ini pengunjung
        // hanya bisa memesan kalau seseorang memberitahunya UUID paket lebih dulu.
        val plans = publicGet("/api/public/orders/${tenant.slug}/plans", visitorIp)
        assertThat(JsonPath.read<List<String>>(plans, "$[*].planId")).contains(planId)

        val receipt = publicPost("/api/public/orders/${tenant.slug}", submission(planId, phone), visitorIp)
        val number = JsonPath.read<String>(receipt, "$.orderNumber")
        assertThat(number).matches("ORD-\\d{4}-\\d{4}")
        // RECEIVED, bukan DRAFT: pesanan yang berhenti di DRAFT tak punya padanan status portal
        // dan pemesannya akan menerima "tidak ditemukan" selamanya saat melacak.
        assertThat(JsonPath.read<String>(receipt, "$.status")).isEqualTo("RECEIVED")
        assertThat(JsonPath.read<String>(receipt, "$.tenantName")).isEqualTo("Tenant ${tenant.slug}")

        val tracked = publicGet(trackUrl(tenant.slug, number, phone), visitorIp)
        assertThat(JsonPath.read<String>(tracked, "$.status")).isEqualTo("RECEIVED")
        assertThat(JsonPath.read<String>(tracked, "$.orderNumber")).isEqualTo(number)
        // DRAFT dilipat keluar dari riwayat publik; yang tersisa persis satu langkah.
        assertThat(JsonPath.read<List<String>>(tracked, "$.timeline[*].status")).containsExactly("RECEIVED")

        /*
         * Yang paling penting di test ini: halaman lacak TIDAK memuat apa pun selain ringkasan
         * status. Nomor pesanan + nomor HP adalah rahasia yang lemah (keduanya beredar di grup
         * WhatsApp), jadi apa pun yang bocor di sini sama saja dengan bocor ke publik.
         */
        assertThat(tracked).doesNotContain(STREET)
        assertThat(tracked).doesNotContain("Bekasi")
        assertThat(tracked).doesNotContain(phone)

        // Prospeknya benar-benar masuk antrean operator, ditandai berasal dari web publik.
        assertThat(
            countRows(
                tenant.id,
                "SELECT count(*) FROM order_lead WHERE tenant_id = :t AND phone = :p AND source = 'PUBLIC_WEB'",
                mapOf("t" to tenant.id, "p" to phone),
            ),
        ).isEqualTo(1)
    }

    @Test
    fun `tracking never crosses a tenant boundary and never accepts the wrong phone`() {
        val a = newTenantAdmin("pubx")
        val b = newTenantAdmin("puby")
        val phone = phone()
        val number = JsonPath.read<String>(
            publicPost("/api/public/orders/${a.slug}", submission(plan(a), phone), ip()),
            "$.orderNumber",
        )

        // Nomor pesanan hanya unik PER TENANT. Dilacak lewat slug tetangga, ia harus hilang
        // sama sekali — bukan sekadar tak menampilkan detail.
        publicGet(trackUrl(b.slug, number, phone), ip(), expected = 404)
        // Nomor benar, HP salah → sama-sama 404, dengan kalimat yang sama.
        publicGet(trackUrl(a.slug, number, phone()), ip(), expected = 404)
        // Slug ngawur juga 404, bukan pesan "tenant tidak ada" yang bisa dipakai memanen slug.
        publicGet(trackUrl("tenant-tidak-ada-${uniq()}", number, phone), ip(), expected = 404)
        // Dan tentu saja nomor yang tak pernah ada.
        publicGet(trackUrl(a.slug, "ORD-9999-9999", phone), ip(), expected = 404)
    }

    @Test
    fun `pressing send twice never produces two orders or two prospects`() {
        val tenant = newTenantAdmin("pubi")
        val planId = plan(tenant)
        val phone = phone()
        val visitorIp = ip()
        val body = submission(planId, phone, extra = ""","requestId":"rid-${uniq()}"""")

        val first = publicPost("/api/public/orders/${tenant.slug}", body, visitorIp)
        val second = publicPost("/api/public/orders/${tenant.slug}", body, visitorIp)

        assertThat(JsonPath.read<String>(second, "$.orderNumber"))
            .isEqualTo(JsonPath.read<String>(first, "$.orderNumber"))
        assertThat(
            countRows(
                tenant.id, "SELECT count(*) FROM order_record WHERE tenant_id = :t",
                mapOf("t" to tenant.id),
            ),
        ).isEqualTo(1)
        // Prospek TIDAK dijaga operation key mana pun di dalam agregatnya; tanpa gerbang di
        // depan, klik kedua meninggalkan prospek yatim di antrean operator meski pesanannya satu.
        assertThat(
            countRows(
                tenant.id, "SELECT count(*) FROM order_lead WHERE tenant_id = :t AND phone = :p",
                mapOf("t" to tenant.id, "p" to phone),
            ),
        ).isEqualTo(1)
    }

    @Test
    fun `the throttle stops a flood of public orders from one address`() {
        val tenant = newTenantAdmin("pubt")
        val planId = plan(tenant)
        val floodIp = ip()

        fun burst() = (1..THROTTLE_BURST).map {
            publicPostStatus("/api/public/orders/${tenant.slug}", submission(planId, phone()), floodIp)
        }

        val first = burst()
        // Lima yang pertama memang harus lewat: rem ini menahan skrip, bukan orang yang salah isi.
        // IP-nya baru, jadi cacahnya mustahil melewati batas sebelum permintaan keenam.
        assertThat(first.take(THROTTLE_LIMIT)).containsOnly(200)

        /*
         * Jendela rem dibulatkan ke kelipatan satu jam, SAMA untuk semua instance. Kalau ia
         * kebetulan bergulir tepat di tengah rentetan, cacahnya kembali nol dan rentetan
         * pertama lolos seluruhnya — jadi rentetannya diulang sekali. Dua pergantian jendela
         * dalam hitungan detik tidak mungkin, jadi ini deterministik, bukan "coba lagi" buta.
         */
        assertThat(first.contains(429) || burst().contains(429)).isTrue()

        // Cacahnya ada di DATABASE, bukan di memori proses — inilah yang membuat dua replika
        // di belakang Caddy tidak diam-diam melipatgandakan batas efektifnya.
        val recorded = asTenant(tenant.id) {
            (
                em.createNativeQuery(
                    """SELECT count(*) FROM public_request_throttle
                       WHERE scope = 'public-order-submit-ip' AND subject = :ip""",
                ).setParameter("ip", floodIp).singleResult as Number
                ).toLong()
        }
        assertThat(recorded).isPositive()
    }

    @Test
    fun `a form that fills the honeypot is rejected and leaves nothing behind`() {
        val tenant = newTenantAdmin("pubh")
        val planId = plan(tenant)
        val phone = phone()

        publicPost(
            "/api/public/orders/${tenant.slug}",
            submission(planId, phone, extra = ""","website":"http://spam.example"""),
            ip(),
            expected = 400,
        )

        assertThat(
            countRows(
                tenant.id, "SELECT count(*) FROM order_lead WHERE tenant_id = :t AND phone = :p",
                mapOf("t" to tenant.id, "p" to phone),
            ),
        ).isZero()
    }

    @Test
    fun `an operator flag reaches the visitor's tracking page and can be lifted again`() {
        val tenant = newTenantAdmin("pubf")
        val phone = phone()
        val number = JsonPath.read<String>(
            publicPost("/api/public/orders/${tenant.slug}", submission(plan(tenant), phone), ip()),
            "$.orderNumber",
        )
        val orderId = orderIdOf(tenant, number)

        post(
            "/api/orders/$orderId/attention", tenant.token,
            """{"flag":"WAITING_CUSTOMER","reason":"Menunggu konfirmasi titik pemasangan",
                "operation":{"namespace":"order.attention","key":"${uniq()}","payloadHash":"${uniq()}"}}""",
            expected = 200,
        )

        val flagged = publicGet(trackUrl(tenant.slug, number, phone), ip())
        assertThat(JsonPath.read<String>(flagged, "$.status")).isEqualTo("WAITING_CUSTOMER")
        assertThat(JsonPath.read<String>(flagged, "$.statusNote")).isEqualTo("Menunggu konfirmasi titik pemasangan")

        // Penanda DILEPAS → pesanan kembali menampilkan status sesungguhnya, bukan macet di
        // "menunggu pelanggan" selamanya.
        post(
            "/api/orders/$orderId/attention", tenant.token,
            """{"flag":null,"operation":{"namespace":"order.attention","key":"${uniq()}","payloadHash":"${uniq()}"}}""",
            expected = 200,
        )
        val cleared = publicGet(trackUrl(tenant.slug, number, phone), ip())
        assertThat(JsonPath.read<String>(cleared, "$.status")).isEqualTo("RECEIVED")
        assertThat(cleared).doesNotContain("Menunggu konfirmasi")
    }

    /**
     * P5.4 + P5.6 dalam satu alur, karena memang satu alur: pengunjung memesan → operator
     * menerima → teknisi memasang → persetujuan menutup pesanan yang sama.
     */
    @Test
    fun `accepting a public order opens one PSB work order and approving it closes the order`() {
        val tenant = newTenantAdmin("pube")
        val planId = plan(tenant)
        val phone = phone()
        val number = JsonPath.read<String>(
            publicPost("/api/public/orders/${tenant.slug}", submission(planId, phone), ip()),
            "$.orderNumber",
        )
        val orderId = orderIdOf(tenant, number)
        val revision = JsonPath.read<Int>(get("/api/orders/$orderId", tenant.token), "$.revision")

        val acceptKey = uniq()
        val accepted = acceptOrder(tenant, orderId, revision, acceptKey)
        assertThat(JsonPath.read<String>(accepted, "$.status")).isEqualTo("ACCEPTED")

        // Prospeknya menjadi pelanggan sungguhan — tepat satu kali.
        assertThat(
            countRows(
                tenant.id,
                "SELECT count(*) FROM order_lead WHERE tenant_id = :t AND phone = :p AND status = 'CONVERTED'",
                mapOf("t" to tenant.id, "p" to phone),
            ),
        ).isEqualTo(1)

        /*
         * Inilah taut yang selama ini hilang: WO PSB yang menunjuk balik ke pesanannya. Tanpa
         * kolom ini terisi, pesanan yang diterima tak pernah menjadi pekerjaan siapa pun dan
         * persetujuan WO tak punya cara menutupnya kembali.
         */
        val workOrders = asTenant(tenant.id) {
            em.createNativeQuery("SELECT id, type FROM work_order WHERE tenant_id = :t AND order_id = :o")
                .setParameter("t", tenant.id).setParameter("o", UUID.fromString(orderId))
                .resultList.map { (it as Array<*>).let { row -> row[0].toString() to row[1].toString() } }
        }
        assertThat(workOrders).hasSize(1)
        assertThat(workOrders.single().second).isEqualTo("PSB")
        val woId = workOrders.single().first

        // Tombol "Terima" ditekan dua kali (atau klien mengulang POST karena timeout) TIDAK
        // boleh membuka WO kedua — teknisi berangkat dua kali ke rumah yang sama.
        acceptOrder(tenant, orderId, revision, acceptKey)
        assertThat(
            countRows(
                tenant.id, "SELECT count(*) FROM work_order WHERE tenant_id = :t AND order_id = :o",
                mapOf("t" to tenant.id, "o" to UUID.fromString(orderId)),
            ),
        ).isEqualTo(1)

        // ---- P5.6: persetujuan WO membawa efek ORDER --------------------------------------
        approveWorkOrder(tenant, woId)

        /*
         * Yang diperiksa adalah PERMINTAAN saga-nya, bukan hasil akhir seluruh saga. Efek
         * dijalankan berurutan menurut urutan enum, dan `PROVISIONING` yang mendahului `ORDER`
         * menuntut sesi BNG yang benar-benar aktif — perangkat yang tak ada di lingkungan test.
         * Yang menjadi pokok P5.6 justru bagian ini: tanpa `ORDER` di daftar efek dan tanpa
         * `orderId` di payload, pesanan tak akan pernah tertutup betapapun mulusnya pemasangan.
         */
        val payload = asTenant(tenant.id) {
            em.createNativeQuery("SELECT payload FROM fulfillment_outbox WHERE tenant_id = :t")
                .setParameter("t", tenant.id).resultList.map { it.toString() }
        }
        assertThat(payload).hasSize(1)
        assertThat(payload.single()).contains("ORDER,PROVISIONING,SUBSCRIPTION,WORK_ORDER")
        assertThat(payload.single()).contains(orderId)

        /*
         * Lalu efek `ORDER` dijalankan persis seperti `FulfillmentEffectApplier.applyOrder`
         * menjalankannya: tanpa principal, dengan revisi yang dibaca sendiri dari pesanannya.
         * Sebelum fase ini jalur ini MUSTAHIL berhasil — `current()` melempar di thread worker
         * dan kegagalannya muncul sebagai "ORDER_EFFECT_REJECTED" yang menuntut rekonsiliasi
         * manual, padahal tak ada yang salah selain tak adanya token.
         */
        val result = asTenant(tenant.id) {
            orders.applyFulfillment(
                OrderFulfillmentCommand(
                    tenantId = tenant.id,
                    orderId = UUID.fromString(orderId),
                    transition = OrderTransition.FULFILL,
                    expectedRevision = orders.fulfillmentRevision(UUID.fromString(orderId)),
                    namespace = "workorder.fulfillment.approve",
                    operationKey = "$woId:test",
                    payloadHash = "hash-$woId",
                    actorId = null,
                ),
            )
        }
        assertThat(result.status).isEqualTo("FULFILLED")

        // Dan pengunjung yang tak pernah punya akun melihatnya selesai.
        val tracked = publicGet(trackUrl(tenant.slug, number, phone), ip())
        assertThat(JsonPath.read<String>(tracked, "$.status")).isEqualTo("COMPLETED")
        assertThat(JsonPath.read<List<String>>(tracked, "$.timeline[*].status")).endsWith("COMPLETED")
    }

    // ---------------------------------------------------------------------------------------
    // Pembantu
    // ---------------------------------------------------------------------------------------

    private fun trackUrl(slug: String, number: String, phone: String) =
        "/api/public/orders/$slug/track?orderNumber=$number&phone=$phone"

    /** Id pesanan menurut operator; pengunjung tak pernah melihat UUID mana pun. */
    private fun orderIdOf(tenant: Tenant, number: String): String =
        JsonPath.read<List<String>>(get("/api/orders?query=$number", tenant.token), "$.content[*].id").single()

    private fun acceptOrder(tenant: Tenant, orderId: String, revision: Int, key: String): String =
        post(
            "/api/orders/$orderId/ACCEPT", tenant.token,
            """{"expectedRevision":$revision,"promotion":{},
                "operation":{"namespace":"order.accept","key":"$key","payloadHash":"hash-$key"}}""",
            expected = 200,
        )

    /** Bawa WO sampai disetujui: tugaskan → kerjakan → selesaikan → setujui orang lain. */
    private fun approveWorkOrder(tenant: Tenant, woId: String) {
        val technicianId = newTechnician(tenant, "Teknisi Pasang")
        post("/api/work-orders/$woId/assign", tenant.token, """{"technicianIds":["$technicianId"]}""", 200)
        post("/api/work-orders/$woId/start", tenant.token, "", 200)
        post("/api/work-orders/$woId/complete", tenant.token, completionBody(woId, tenant.token), 200)
        // Penyetuju WAJIB orang lain; penyelesai yang menyetujui pekerjaannya sendiri ditolak.
        post("/api/work-orders/$woId/approve", newApprover(tenant), """{"note":"Terpasang rapi"}""", 200)
    }

    private fun newTechnician(tenant: Tenant, name: String): String {
        val roles = get("/api/roles", tenant.token)
        val names = JsonPath.read<List<String>>(roles, "$[*].name")
        val ids = JsonPath.read<List<String>>(roles, "$[*].id")
        val roleId = ids[names.indexOf("Teknisi")]
        val created = post(
            "/api/users", tenant.token,
            """{"email":"tech-${uniq()}@x.test","name":"$name","password":"$pass","roleIds":["$roleId"]}""",
        )
        return JsonPath.read(created, "$.id")
    }

    private fun newApprover(tenant: Tenant): String {
        val roles = get("/api/roles", tenant.token)
        val names = JsonPath.read<List<String>>(roles, "$[*].name")
        val ids = JsonPath.read<List<String>>(roles, "$[*].id")
        val roleIndex = names.indexOfFirst { it.contains("Admin", ignoreCase = true) }.takeIf { it >= 0 } ?: 0
        val email = "approver-${uniq()}@x.test"
        post(
            "/api/users", tenant.token,
            """{"email":"$email","name":"Approver","password":"$pass","roleIds":["${ids[roleIndex]}"]}""",
        )
        return login(tenant.slug, email)
    }

    /** Disalin apa adanya dari `WorkOrderIT`: bukti kerja PSB yang lengkap + revisi otoritatifnya. */
    private fun completionBody(workOrderId: String, token: String): String {
        val evidenceRevisionIds = EVIDENCE_KINDS.associateWith { kind ->
            val evidence = mockMvc.perform(
                multipart("/api/work-orders/$workOrderId/evidence")
                    .file(MockMultipartFile("file", "$kind.png", MediaType.IMAGE_PNG_VALUE, PNG))
                    .param("kind", kind)
                    .header("Authorization", "Bearer $token"),
            ).andExpect(status().isCreated).andReturn().response.contentAsString
            JsonPath.read<String>(evidence, "$.revisionId")
        }
        val acknowledgement = mockMvc.perform(
            multipart(HttpMethod.PUT, "/api/work-orders/$workOrderId/signature")
                .file(MockMultipartFile("file", "acknowledgement.png", MediaType.IMAGE_PNG_VALUE, PNG))
                .param("signerName", "Pelanggan")
                .param("correctionReason", "Persetujuan pelanggan diperbarui")
                .header("Authorization", "Bearer $token"),
        ).andExpect(status().isOk).andReturn().response.contentAsString
        val acknowledgementRevisionId = JsonPath.read<String>(acknowledgement, "$.revisionId")
        val revision = JsonPath.read<String>(get("/api/work-orders/$workOrderId/proof-of-work", token), "$.revision")
        val artifacts = (EVIDENCE_KINDS + "CUSTOMER_ACKNOWLEDGEMENT").joinToString(",") { kind ->
            val revisionId =
                if (kind == "CUSTOMER_ACKNOWLEDGEMENT") acknowledgementRevisionId else evidenceRevisionIds.getValue(kind)
            """{"kind":"$kind","revisionId":"$revisionId"}"""
        }
        return """{"proofRevision":"$revision","artifacts":[$artifacts]}"""
    }

    private companion object {
        /** Alamat pemasangan; dipakai juga sebagai umpan untuk membuktikan ia TIDAK bocor. */
        const val STREET = "Jl. Anggrek Tak Boleh Bocor 12"
        const val THROTTLE_LIMIT = 5
        const val THROTTLE_BURST = 6
        val EVIDENCE_KINDS = listOf(
            "FAT", "ODP", "DROPCORE", "ONT", "ONU",
            "OPTICAL_BEFORE", "OPTICAL_AFTER", "TECHNICIAN_SIGNATURE", "LOCATION",
        )
        val PNG = byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10, 1, 2, 3, 4, 5)
    }
}

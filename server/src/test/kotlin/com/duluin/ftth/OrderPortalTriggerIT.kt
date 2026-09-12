package com.duluin.ftth

import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.iam.application.port.inbound.OnboardTenantCommand
import com.duluin.ftth.iam.application.port.inbound.OnboardTenantUseCase
import com.duluin.ftth.order.OrderFulfillmentStallResolved
import com.duluin.ftth.order.OrderFulfillmentStalled
import com.duluin.ftth.order.domain.model.OrderPortalNarrative
import com.jayway.jsonpath.JsonPath
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.ApplicationEventPublisher
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.util.UUID

/**
 * PEMICU OTOMATIS penanda portal pesanan.
 *
 * Sebelum fase ini `WAITING_CUSTOMER` dan `REQUIRES_ATTENTION` hanya bisa dipasang operator lewat
 * tombol, dan tak ada satu pun pemanggil otomatis. Yang dibuktikan di sini persis jarak antara
 * "jalurnya ada" dan "jalurnya benar-benar dilewati":
 *
 * 1. Kunjungan yang gagal KARENA PELANGGAN menandai pesanannya sendiri, dengan kalimat baku —
 *    bukan catatan internal teknisi.
 * 2. Kunjungan yang gagal karena urusan KAMI tidak menyentuh pelanggan sama sekali.
 * 3. Penanda yang dipasang OPERATOR kebal terhadap otomasi; ini satu-satunya hal yang menjaga
 *    keputusan manusia yang tahu lebih banyak daripada mesin.
 * 4. Tombol "tidak bisa dihubungi" menolak tiga keadaan di mana bolanya masih di tangan kami.
 * 5. Saga fulfillment yang macet memunculkan `REQUIRES_ATTENTION`, dan rekonsiliasinya melepasnya.
 *
 * Semuanya lewat HTTP dan Postgres sungguhan: yang paling mungkin patah bukan logikanya melainkan
 * sambungannya — event yang tak pernah terdengar, `TenantContext` yang tak terpasang di listener
 * (query ber-RLS memulangkan NOL baris TANPA error), atau kolom baru yang tak ikut tersimpan.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Suppress("LargeClass")
class OrderPortalTriggerIT {

    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var onboarding: OnboardTenantUseCase
    @Autowired private lateinit var txManager: PlatformTransactionManager
    @Autowired private lateinit var events: ApplicationEventPublisher

    @PersistenceContext private lateinit var em: EntityManager

    // -------------------------------------------------------------------------------------
    // 1 & 2: kunjungan gagal
    // -------------------------------------------------------------------------------------

    @Test
    fun `a visit that fails because of the customer flags the order on the tracking page`() {
        val world = acceptedOrderWithVisit("trgc")

        cancelVisit(world, "CUSTOMER_NOT_PRESENT", TECHNICIAN_NOTE)

        val tracked = track(world)
        assertThat(JsonPath.read<String>(tracked, "$.status")).isEqualTo("WAITING_CUSTOMER")
        assertThat(JsonPath.read<String>(tracked, "$.statusNote"))
            .isEqualTo(OrderPortalNarrative.VISIT_CUSTOMER_NOT_PRESENT.sentence)

        /*
         * Yang paling mudah bocor di fitur ini: catatan teknisi. Ia ditulis untuk rekan sekantor
         * ("gak ada orang, tetangga bilang mudik") dan sekaligus kalimat yang tak boleh dibaca
         * pelanggan yang bersangkutan. Ia WAJIB berhenti di `fieldservice_visit`.
         */
        assertThat(tracked).doesNotContain(TECHNICIAN_NOTE)
        assertThat(
            countRows(
                world.tenant.id,
                "SELECT count(*) FROM fieldservice_visit WHERE tenant_id = :t AND id = :v AND cancellation_reason = :r",
                mapOf("t" to world.tenant.id, "v" to UUID.fromString(world.visitId), "r" to TECHNICIAN_NOTE),
            ),
        ).isEqualTo(1)

        // Sumber penandanya SISTEM. Tanpa kolom ini otomasi tak punya cara membedakan penanda
        // buatannya sendiri dari keputusan operator, dan pelepasan otomatis akan menghapus
        // keduanya.
        assertThat(flagSource(world)).isEqualTo("SYSTEM")

        // Teknisi yang melaporkan gagalnya tercatat sebagai pelakunya — riwayat yang berkata
        // "ditandai oleh entah siapa" tak bisa ditindaklanjuti supervisor.
        assertThat(
            countRows(
                world.tenant.id,
                """SELECT count(*) FROM order_audit
                   WHERE tenant_id = :t AND order_id = :o AND event_type = 'ORDER_FLAGGED'
                     AND operation_namespace = 'order-automation' AND actor_id = :a""",
                mapOf("t" to world.tenant.id, "o" to UUID.fromString(world.orderId), "a" to UUID.fromString(world.technicianId)),
            ),
        ).isEqualTo(1)
    }

    @Test
    fun `a visit that fails because of us leaves the customer alone`() {
        val world = acceptedOrderWithVisit("trgi")

        cancelVisit(world, "TECHNICAL_BLOCKER", "ODP penuh, tunggu ekspansi")

        /*
         * Menyuruh pelanggan bertindak atas keterlambatan yang KAMI sebabkan adalah cara tercepat
         * kehilangan kepercayaannya — dan halaman lacaknya tak punya cara membantah.
         */
        val tracked = track(world)
        // ACCEPTED dipetakan ke REVIEWING di portal — apa adanya, tanpa penanda apa pun.
        assertThat(JsonPath.read<String>(tracked, "$.status")).isEqualTo("REVIEWING")
        assertThat(tracked).doesNotContain("Mohon hubungi kami")
        assertThat(flagSource(world)).isNull()
    }

    @Test
    fun `an operator flag is never overwritten by the automatic trigger`() {
        val world = acceptedOrderWithVisit("trgo")
        val operatorSentence = "Kami menunggu konfirmasi Anda soal titik pemasangan di lantai dua"
        post(
            "/api/orders/${world.orderId}/attention", world.tenant.token,
            """{"flag":"REQUIRES_ATTENTION","reason":"$operatorSentence",
                "operation":{"namespace":"order.attention","key":"${uniq()}","payloadHash":"${uniq()}"}}""",
            expected = 200,
        )

        cancelVisit(world, "PREMISE_LOCKED", "Rumah digembok, pagar tinggi")

        /*
         * Operator yang menekan tombolnya baru saja MENELEPON pelanggannya dan tahu sesuatu yang
         * tidak diketahui mesin. Otomasi yang menimpa kalimatnya menghapus satu-satunya informasi
         * yang benar di halaman itu.
         */
        val tracked = track(world)
        assertThat(JsonPath.read<String>(tracked, "$.status")).isEqualTo("REQUIRES_ATTENTION")
        assertThat(JsonPath.read<String>(tracked, "$.statusNote")).isEqualTo(operatorSentence)
        assertThat(flagSource(world)).isEqualTo("OPERATOR")
    }

    // -------------------------------------------------------------------------------------
    // 4: tombol "pelanggan tidak bisa dihubungi"
    // -------------------------------------------------------------------------------------

    @Test
    fun `unreachable is refused while the ball is still in our court`() {
        val tenant = newTenantAdmin("trgu")
        val order = publicOrder(tenant)

        // (a) Belum diterima: yang menghambat adalah peninjauan KAMI, bukan pelanggan.
        assertThat(unreachableDetail(tenant, order.orderId, expected = 409))
            .contains("ACCEPTED")

        val revision = JsonPath.read<Int>(get("/api/orders/${order.orderId}", tenant.token), "$.revision")
        acceptOrder(tenant, order.orderId, revision, uniq())

        // (b) Baru saja diterima. Tanpa ambang ini pelanggan yang memesan lima menit lalu membuka
        // halaman lacaknya dan membaca bahwa DIALAH yang tak bisa dihubungi.
        assertThat(unreachableDetail(tenant, order.orderId, expected = 409))
            .contains("tunggu minimal")

        // (c) Sudah punya janji temu → pelanggan jelas sudah berhasil dihubungi. Janji temunya
        // ditulis langsung ke basis data karena tak ada endpoint yang memasangnya pada pesanan
        // ber-status ACCEPTED, sementara penjaganya tetap harus terbukti menahan.
        backdateAcceptance(tenant.id, order.orderId)
        execute(tenant.id, "UPDATE order_record SET appointment_starts_at = :s, appointment_ends_at = :e WHERE tenant_id = :t AND id = :o",
            mapOf("s" to Instant.now().plusSeconds(86_400), "e" to Instant.now().plusSeconds(90_000), "t" to tenant.id, "o" to UUID.fromString(order.orderId)))
        assertThat(unreachableDetail(tenant, order.orderId, expected = 409))
            .contains("janji temu")

        execute(tenant.id, "UPDATE order_record SET appointment_starts_at = NULL, appointment_ends_at = NULL WHERE tenant_id = :t AND id = :o",
            mapOf("t" to tenant.id, "o" to UUID.fromString(order.orderId)))

        // Barulah tombolnya boleh ditekan.
        val key = uniq()
        post(
            "/api/orders/${order.orderId}/unreachable", tenant.token,
            """{"note":"$UNREACHABLE_NOTE","operation":{"namespace":"order.unreachable","key":"$key","payloadHash":"hash-$key"}}""",
            expected = 200,
        )

        val tracked = publicGet(trackUrl(tenant.slug, order.number, order.phone), ip())
        assertThat(JsonPath.read<String>(tracked, "$.status")).isEqualTo("WAITING_CUSTOMER")
        // Kalimatnya BAKU, bukan ketikan operator: itulah gunanya endpoint ini terpisah dari
        // `/attention`.
        assertThat(JsonPath.read<String>(tracked, "$.statusNote"))
            .isEqualTo(OrderPortalNarrative.CUSTOMER_UNREACHABLE.sentence)
        // Catatan internal operator berhenti di riwayat.
        assertThat(tracked).doesNotContain(UNREACHABLE_NOTE)
        assertThat(
            countRows(
                tenant.id,
                "SELECT count(*) FROM order_audit WHERE tenant_id = :t AND order_id = :o AND payload LIKE :n",
                mapOf("t" to tenant.id, "o" to UUID.fromString(order.orderId), "n" to "%$UNREACHABLE_NOTE%"),
            ),
        ).isEqualTo(1)

        // Sumbernya OPERATOR meski kalimatnya ditulis sistem: yang MEMUTUSKAN tetap manusia, dan
        // otomasi tidak boleh melepasnya diam-diam.
        assertThat(flagSourceOf(tenant.id, order.orderId)).isEqualTo("OPERATOR")

        // Klien yang mengulang POST karena timeout tidak boleh menambah baris riwayat kedua.
        post(
            "/api/orders/${order.orderId}/unreachable", tenant.token,
            """{"note":"$UNREACHABLE_NOTE","operation":{"namespace":"order.unreachable","key":"$key","payloadHash":"hash-$key"}}""",
            expected = 200,
        )
        assertThat(
            countRows(
                tenant.id,
                "SELECT count(*) FROM order_audit WHERE tenant_id = :t AND order_id = :o AND operation_key = :k",
                mapOf("t" to tenant.id, "o" to UUID.fromString(order.orderId), "k" to key),
            ),
        ).isEqualTo(1)
    }

    // -------------------------------------------------------------------------------------
    // 5: saga fulfillment macet
    // -------------------------------------------------------------------------------------

    @Test
    fun `a stalled fulfillment marks the order for review and resolving it releases the mark`() {
        val tenant = newTenantAdmin("trgs")
        val order = publicOrder(tenant)
        val stall = OrderFulfillmentStalled(
            tenantId = tenant.id,
            orderId = UUID.fromString(order.orderId),
            namespace = "workorder.fulfillment.approve",
            operationKey = "wo-${uniq()}",
            outcome = "ORDER_EFFECT_REJECTED",
            occurredAt = Instant.now(),
        )

        events.publishEvent(stall)

        val flagged = publicGet(trackUrl(tenant.slug, order.number, order.phone), ip())
        assertThat(JsonPath.read<String>(flagged, "$.status")).isEqualTo("REQUIRES_ATTENTION")
        assertThat(JsonPath.read<String>(flagged, "$.statusNote"))
            .isEqualTo(OrderPortalNarrative.FULFILLMENT_NEEDS_REVIEW.sentence)
        // Pesan kegagalan INTERNAL tidak pernah sampai ke pelanggan; ia tak bisa berbuat apa pun
        // dengan "ORDER_EFFECT_REJECTED" selain cemas.
        assertThat(flagged).doesNotContain("ORDER_EFFECT_REJECTED")
        assertThat(flagSourceOf(tenant.id, order.orderId)).isEqualTo("SYSTEM")

        // Peristiwa yang sama terdengar dua kali (worker outbox mengulang) tidak menambah riwayat.
        events.publishEvent(stall)
        assertThat(
            countRows(
                tenant.id,
                """SELECT count(*) FROM order_audit WHERE tenant_id = :t AND order_id = :o
                   AND event_type = 'ORDER_FLAGGED' AND operation_namespace = 'order-automation'""",
                mapOf("t" to tenant.id, "o" to UUID.fromString(order.orderId)),
            ),
        ).isEqualTo(1)

        events.publishEvent(
            OrderFulfillmentStallResolved(tenant.id, UUID.fromString(order.orderId), stall.namespace, stall.operationKey, Instant.now()),
        )

        val cleared = publicGet(trackUrl(tenant.slug, order.number, order.phone), ip())
        assertThat(JsonPath.read<String>(cleared, "$.status")).isEqualTo("RECEIVED")
        assertThat(flagSourceOf(tenant.id, order.orderId)).isNull()
    }

    @Test
    fun `resolving a stall never lifts a flag an operator put there`() {
        val tenant = newTenantAdmin("trgr")
        val order = publicOrder(tenant)
        val operatorSentence = "Mohon kirim ulang foto KTP; yang kami terima tidak terbaca"
        post(
            "/api/orders/${order.orderId}/attention", tenant.token,
            """{"flag":"REQUIRES_ATTENTION","reason":"$operatorSentence",
                "operation":{"namespace":"order.attention","key":"${uniq()}","payloadHash":"${uniq()}"}}""",
            expected = 200,
        )

        events.publishEvent(
            OrderFulfillmentStallResolved(tenant.id, UUID.fromString(order.orderId), "workorder.fulfillment.approve", "wo-${uniq()}", Instant.now()),
        )

        /*
         * Penanda operator dan penanda sistem kebetulan berbentuk sama. Kalau pelepasan otomatis
         * hanya mencocokkan JENIS penandanya, saga yang direkonsiliasi akan menghapus permintaan
         * dokumen yang ditulis operator — dan pelanggan tak pernah tahu ia harus mengirim ulang.
         */
        val tracked = publicGet(trackUrl(tenant.slug, order.number, order.phone), ip())
        assertThat(JsonPath.read<String>(tracked, "$.status")).isEqualTo("REQUIRES_ATTENTION")
        assertThat(JsonPath.read<String>(tracked, "$.statusNote")).isEqualTo(operatorSentence)
        assertThat(flagSourceOf(tenant.id, order.orderId)).isEqualTo("OPERATOR")
    }

    // -------------------------------------------------------------------------------------
    // Pembantu
    // -------------------------------------------------------------------------------------

    private data class Tenant(val id: UUID, val slug: String, val token: String)
    private data class PublicOrder(val orderId: String, val number: String, val phone: String)
    private data class VisitWorld(
        val tenant: Tenant,
        val order: PublicOrder,
        val visitId: String,
        val visitRevision: Long,
        val technicianId: String,
    ) {
        val orderId: String get() = order.orderId
    }

    private val pass = "secret12345"
    private fun uniq() = UUID.randomUUID().toString().replace("-", "").substring(0, 8)

    /** Ember rem laju publik tinggal di DATABASE dan tak pernah dikosongkan antar test. */
    private fun ip() = "198.51.${(0..255).random()}.${(1..254).random()}"
    private fun phone() = "0812" + (1..8).joinToString("") { (0..9).random().toString() }

    private fun login(slug: String, email: String): String {
        val json = mockMvc.perform(
            post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("""{"tenantSlug":"$slug","email":"$email","password":"$pass"}"""),
        ).andExpect { assertThat(it.response.status).isEqualTo(200) }.andReturn().response.contentAsString
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

    private fun publicGet(url: String, ip: String, expected: Int = 200): String =
        mockMvc.perform(get(url).with { it.remoteAddr = ip; it })
            .andExpect { assertThat(it.response.status).isEqualTo(expected) }
            .andReturn().response.contentAsString

    private fun trackUrl(slug: String, number: String, phone: String) =
        "/api/public/orders/$slug/track?orderNumber=$number&phone=$phone"

    private fun track(world: VisitWorld) =
        publicGet(trackUrl(world.tenant.slug, world.order.number, world.order.phone), ip())

    /** Pesanan yang benar-benar datang dari pintu publik, supaya halaman lacaknya bisa dibuka. */
    private fun publicOrder(tenant: Tenant): PublicOrder {
        val planId = JsonPath.read<String>(
            post(
                "/api/catalog/plans", tenant.token,
                """{"name":"Paket ${uniq()}","description":null,"price":150000,
                    "downMbps":20,"upMbps":10,"serviceTypes":["PPPOE"]}""",
            ),
            "$.id",
        )
        val phone = phone()
        val receipt = mockMvc.perform(
            post("/api/public/orders/${tenant.slug}").contentType(MediaType.APPLICATION_JSON)
                .content(
                    """{"name":"Pengunjung Uji","phone":"$phone","planId":"$planId",
                        "address":"Jl. Melati 7","city":"Bekasi","postalCode":"17111"}""",
                )
                .with { it.remoteAddr = ip(); it },
        ).andExpect { assertThat(it.response.status).isEqualTo(200) }.andReturn().response.contentAsString
        val number = JsonPath.read<String>(receipt, "$.orderNumber")
        val orderId = JsonPath.read<List<String>>(get("/api/orders?query=$number", tenant.token), "$.content[*].id").single()
        return PublicOrder(orderId, number, phone)
    }

    private fun acceptOrder(tenant: Tenant, orderId: String, revision: Int, key: String) =
        post(
            "/api/orders/$orderId/ACCEPT", tenant.token,
            """{"expectedRevision":$revision,"promotion":{},
                "operation":{"namespace":"order.accept","key":"$key","payloadHash":"hash-$key"}}""",
            expected = 200,
        )

    /**
     * Pesanan publik → diterima → WO PSB → teknisi ditugaskan → kunjungan direncanakan.
     *
     * Seluruh rantai ini WAJIB dilalui sungguhan: `FieldServiceService.create` menolak kunjungan
     * yang penugasan WO-nya tidak aktif atau yang `orderId`-nya tidak cocok, dan justru taut
     * itulah yang membawa pembatalan kunjungan kembali ke pesanan yang benar.
     */
    private fun acceptedOrderWithVisit(prefix: String): VisitWorld {
        val tenant = newTenantAdmin(prefix)
        val order = publicOrder(tenant)
        val revision = JsonPath.read<Int>(get("/api/orders/${order.orderId}", tenant.token), "$.revision")
        acceptOrder(tenant, order.orderId, revision, uniq())

        val workOrder = asTenant(tenant.id) {
            em.createNativeQuery("SELECT id, customer_id FROM work_order WHERE tenant_id = :t AND order_id = :o")
                .setParameter("t", tenant.id).setParameter("o", UUID.fromString(order.orderId))
                .resultList.map { (it as Array<*>).let { row -> row[0].toString() to row[1].toString() } }.single()
        }
        val workOrderId = workOrder.first
        val technicianId = newTechnician(tenant)
        post("/api/work-orders/$workOrderId/assign", tenant.token, """{"technicianIds":["$technicianId"]}""", 200)

        /*
         * `orderId` di sini diisi id PELANGGAN, bukan id pesanan — dan itu memang yang dituntut
         * `FieldServiceService.create`, yang mencocokkannya dengan `WorkOrderAssignmentRef.orderId`
         * (berisi `customerId` work order-nya). Disalin apa adanya dari `WorkOrderIT` supaya test
         * ini menguji jalur yang BENAR-BENAR dipakai aplikasi teknisi, bukan jalur ideal yang
         * selalu 409. Justru karena kolom itu berbohong, pemicu penanda portal mengambil id
         * pesanan dari work order-nya, bukan dari kunjungan.
         */
        val key = uniq()
        val visit = post(
            "/api/v1/fieldservice/visits", tenant.token,
            """{"orderId":"${workOrder.second}","workOrderId":"$workOrderId","technicianId":"$technicianId",
                "plannedAt":"${Instant.now().plusSeconds(3600)}","namespace":"fieldservice.visit",
                "operationKey":"$key","payloadHash":"hash-$key","revision":0}""",
            expected = 200,
        )
        return VisitWorld(
            tenant = tenant,
            order = order,
            visitId = JsonPath.read(visit, "$.id"),
            visitRevision = JsonPath.read<Int>(visit, "$.revision").toLong(),
            technicianId = technicianId,
        )
    }

    private fun cancelVisit(world: VisitWorld, cause: String, reason: String) {
        val key = uniq()
        post(
            "/api/v1/fieldservice/visits/${world.visitId}/cancel", world.tenant.token,
            """{"namespace":"fieldservice.visit","operationKey":"$key","payloadHash":"hash-$key",
                "revision":${world.visitRevision},"cause":"$cause","reason":"$reason"}""",
            expected = 200,
        )
    }

    private fun newTechnician(tenant: Tenant): String {
        val roles = get("/api/roles", tenant.token)
        val names = JsonPath.read<List<String>>(roles, "$[*].name")
        val ids = JsonPath.read<List<String>>(roles, "$[*].id")
        val roleId = ids[names.indexOf("Teknisi")]
        val created = post(
            "/api/users", tenant.token,
            """{"email":"tech-${uniq()}@x.test","name":"Teknisi Pasang","password":"$pass","roleIds":["$roleId"]}""",
        )
        return JsonPath.read(created, "$.id")
    }

    private fun unreachableDetail(tenant: Tenant, orderId: String, expected: Int): String {
        val key = uniq()
        val body = post(
            "/api/orders/$orderId/unreachable", tenant.token,
            """{"note":null,"operation":{"namespace":"order.unreachable","key":"$key","payloadHash":"hash-$key"}}""",
            expected = expected,
        )
        return JsonPath.read(body, "$.detail")
    }

    /**
     * Mundurkan jejak PENERIMAAN pesanan.
     *
     * Ambang diamnya dibaca dari `order_audit`, bukan dari kolom pesanan, jadi inilah satu-satunya
     * cara menguji jalur suksesnya tanpa menunggu tiga hari — dan tanpa `@SpringBootTest(properties)`
     * yang akan memaksa konteks Spring kedua dibangun hanya untuk satu test.
     */
    private fun backdateAcceptance(tenantId: UUID, orderId: String) = execute(
        tenantId,
        "UPDATE order_audit SET occurred_at = now() - interval '10 days' WHERE tenant_id = :t AND order_id = :o",
        mapOf("t" to tenantId, "o" to UUID.fromString(orderId)),
    )

    private fun flagSource(world: VisitWorld) = flagSourceOf(world.tenant.id, world.orderId)

    private fun flagSourceOf(tenantId: UUID, orderId: String): String? = asTenant(tenantId) {
        em.createNativeQuery("SELECT portal_flag_source FROM order_record WHERE tenant_id = :t AND id = :o")
            .setParameter("t", tenantId).setParameter("o", UUID.fromString(orderId))
            .resultList.single()?.toString()
    }

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

    private fun execute(tenantId: UUID, sql: String, params: Map<String, Any>) = asTenant(tenantId) {
        val query = em.createNativeQuery(sql)
        params.forEach { (name, value) -> query.setParameter(name, value) }
        query.executeUpdate()
    }

    private companion object {
        /** Catatan lapangan yang BENAR secara internal dan HARAM dibaca pelanggan. */
        const val TECHNICIAN_NOTE = "gak ada orang, tetangga bilang mudik sampai minggu depan"
        const val UNREACHABLE_NOTE = "3x ditelepon, selalu tidak diangkat"
    }
}

package com.duluin.ftth

import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.iam.application.port.inbound.OnboardTenantCommand
import com.duluin.ftth.iam.application.port.inbound.OnboardTenantUseCase
import com.duluin.ftth.iam.domain.catalog.PermissionCatalog
import com.duluin.ftth.order.application.port.outbound.OrderCustomerProjection
import com.jayway.jsonpath.JsonPath
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID

/**
 * Fondasi pesanan P4: calon pelanggan, nomor pesanan, riwayat, dan riwayat portal yang DURABEL.
 *
 * Yang dijaga di sini justru hal-hal yang tak bisa dibuktikan unit test:
 * 1. `order_lead` benar-benar terisolasi RLS — prospek tenant lain tak terlihat sama sekali.
 * 2. Riwayat pesanan pelanggan masih benar dibaca dari TRANSAKSI BARU. Pendahulunya
 *    `ConcurrentHashMap` di dalam proses, jadi restart aplikasi mengosongkan riwayat semua
 *    pelanggan tanpa satu pun error muncul.
 * 3. Nomor pesanan tak pernah kembar walau pesanan dibuat beruntun.
 * 4. `order_audit` benar-benar DITULIS. Sebelum fase ini tabelnya ada tapi kosong selamanya.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OrderIT {

    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var onboarding: OnboardTenantUseCase
    @Autowired private lateinit var txManager: PlatformTransactionManager
    @Autowired private lateinit var projection: OrderCustomerProjection

    @PersistenceContext private lateinit var em: EntityManager

    private val pass = "secret12345"
    private fun uniq() = UUID.randomUUID().toString().replace("-", "").substring(0, 8)

    private data class Tenant(val id: UUID, val slug: String, val token: String)

    private fun newTenantAdmin(prefix: String): Tenant {
        val slug = "$prefix${uniq()}"
        val admin = "admin@$slug.test"
        val tenantId = onboarding.onboard(OnboardTenantCommand(slug, "Tenant $slug", admin, "Admin", pass)).tenant.id
        val json = mockMvc.perform(
            post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("""{"email":"$admin","password":"$pass"}"""),
        ).andExpect { assertThat(it.response.status).isEqualTo(200) }.andReturn().response.contentAsString
        return Tenant(tenantId, slug, JsonPath.read(json, "$.accessToken"))
    }

    private fun post(url: String, token: String, body: String, expected: Int = 201): String =
        mockMvc.perform(
            post(url).header("Authorization", "Bearer $token")
                .contentType(MediaType.APPLICATION_JSON).content(body),
        ).andExpect { assertThat(it.response.status).isEqualTo(expected) }
            .andReturn().response.contentAsString

    private fun patch(url: String, token: String, body: String, expected: Int = 200): String =
        mockMvc.perform(
            patch(url).header("Authorization", "Bearer $token")
                .contentType(MediaType.APPLICATION_JSON).content(body),
        ).andExpect { assertThat(it.response.status).isEqualTo(expected) }
            .andReturn().response.contentAsString

    private fun get(url: String, token: String, expected: Int = 200): String =
        mockMvc.perform(get(url).header("Authorization", "Bearer $token"))
            .andExpect { assertThat(it.response.status).isEqualTo(expected) }
            .andReturn().response.contentAsString

    /**
     * `!!` SENGAJA tidak dipakai di sini: sebagian assertion justru MENGHARAPKAN null
     * (mis. pesanan tenant lain yang memang tak boleh terbaca). Dengan `!!`, hasil null yang
     * benar akan meledak jadi NPE dan tesnya gagal karena RLS-nya bekerja — persis terbalik.
     */
    @Suppress("UNCHECKED_CAST")
    private fun <T> asTenant(tenantId: UUID, block: () -> T): T =
        TenantContext.runAs(tenantId) { TransactionTemplate(txManager).execute { block() } as T }

    /** Nomor HP acak tapi tetap berbentuk nomor sungguhan — domain menolak yang bukan angka. */
    private fun phone() = "0812" + (1..8).joinToString("") { (0..9).random().toString() }

    private fun createLead(tenant: Tenant, name: String = "Budi Prospek"): String =
        JsonPath.read(
            post(
                "/api/orders/leads", tenant.token,
                """{"name":"$name","phone":"${phone()}","address":"Jl. Prospek 1"}""",
            ),
            "$.id",
        )

    private fun createCustomer(tenant: Tenant): String =
        JsonPath.read(
            post(
                "/api/customers", tenant.token,
                """{"code":"C-${uniq().uppercase()}","name":"Pelanggan Uji","address":"Jl. Uji",
                    "location":{"longitude":106.9,"latitude":-6.2}}""",
            ),
            "$.id",
        )

    /** Pesanan baru untuk pemesan mana pun; `requester` adalah potongan JSON customerId atau leadId. */
    private fun createOrder(tenant: Tenant, requester: String, key: String = uniq()): String =
        post(
            "/api/orders", tenant.token,
            """{$requester,"lines":[{"catalogItemId":"${UUID.randomUUID()}","description":"Paket 100 Mbps","quantity":1}],
                "serviceAddress":{"address":"Jl. Merdeka 1","city":"Bekasi","postalCode":"17111"},
                "operation":{"namespace":"order.create","key":"$key","payloadHash":"hash-$key"}}""",
        )

    private fun transition(tenant: Tenant, orderId: String, name: String, revision: Int, extra: String = ""): String =
        post(
            "/api/orders/$orderId/$name", tenant.token,
            """{"expectedRevision":$revision$extra,
                "operation":{"namespace":"order.$name","key":"${uniq()}","payloadHash":"${uniq()}"}}""",
            expected = 200,
        )

    @Test
    fun `order permissions are registered`() {
        assertThat(PermissionCatalog.codes).contains(
            "order.order.view",
            "order.order.create",
            "order.order.manage",
            "order.lead.view",
            "order.lead.manage",
            "order.import.manage",
        )
    }

    @Test
    fun `RLS hides order lead rows owned by another tenant`() {
        val a = newTenantAdmin("orla")
        val b = newTenantAdmin("orlb")
        val leadId = UUID.randomUUID()

        asTenant(a.id) {
            em.createNativeQuery(
                """INSERT INTO order_lead (id, tenant_id, name, phone, source, status)
                   VALUES (:id, :tenant, :name, :phone, 'OPERATOR', 'NEW')""",
            ).setParameter("id", leadId).setParameter("tenant", a.id)
                .setParameter("name", "Prospek A").setParameter("phone", "081200000001")
                .executeUpdate()
        }

        fun visibleTo(tenantId: UUID) = asTenant(tenantId) {
            (
                em.createNativeQuery("SELECT count(*) FROM order_lead WHERE id = :id")
                    .setParameter("id", leadId).singleResult as Number
                ).toLong()
        }

        assertThat(visibleTo(a.id)).isEqualTo(1)
        assertThat(visibleTo(b.id)).isZero()
        // Pintu HTTP pun ikut tertutup — 404, bukan 403: keberadaan prospek tetangga tak boleh bocor.
        get("/api/orders/leads/$leadId", b.token, expected = 404)
    }

    @Test
    fun `customer order history is still correct when read from a brand new transaction`() {
        val tenant = newTenantAdmin("orph")
        val customerId = createCustomer(tenant)
        val created = createOrder(tenant, """"customerId":"$customerId"""")
        val orderId = JsonPath.read<String>(created, "$.id")

        // DRAFT belum boleh terlihat pelanggan: pesanan yang belum dikirim bukan miliknya.
        assertThat(asTenant(tenant.id) { projection.findByCustomer(tenant.id, UUID.fromString(customerId)) }).isEmpty()

        transition(tenant, orderId, "SUBMIT", 0)

        // Transaksi BARU, konteks tenant baru — meniru proses yang baru saja direstart.
        val history = asTenant(tenant.id) { projection.findByCustomer(tenant.id, UUID.fromString(customerId)) }
        assertThat(history).hasSize(1)
        assertThat(history.single().status.name).isEqualTo("RECEIVED")
        assertThat(history.single().orderNumber).startsWith("ORD-")

        // Pelanggan lain tak pernah melihat pesanan ini, dan tenant lain pun tidak.
        assertThat(asTenant(tenant.id) { projection.findByCustomer(tenant.id, UUID.randomUUID()) }).isEmpty()
        assertThat(asTenant(tenant.id) { projection.find(tenant.id, UUID.randomUUID(), UUID.fromString(orderId)) }).isNull()
    }

    @Test
    fun `repeated order creation never reuses a number`() {
        val tenant = newTenantAdmin("ornum")
        val leadId = createLead(tenant)
        val numbers = (1..5).map {
            JsonPath.read<String>(createOrder(tenant, """"leadId":"$leadId""""), "$.orderNumber")
        }

        assertThat(numbers).allMatch { it.matches(Regex("ORD-\\d{4}-\\d{4}")) }
        // Nomor kembar = UNIQUE (tenant_id, order_number) menolak pesanan dan permintaan hilang.
        assertThat(numbers.toSet()).hasSize(numbers.size)
    }

    @Test
    fun `the timeline records every transition with its reason`() {
        val tenant = newTenantAdmin("ortl")
        val leadId = createLead(tenant)
        val orderId = JsonPath.read<String>(createOrder(tenant, """"leadId":"$leadId""""), "$.id")

        transition(tenant, orderId, "SUBMIT", 0)
        transition(tenant, orderId, "REJECT", 1, ""","reason":"Alamat di luar jangkauan"""")

        val timeline = get("/api/orders/$orderId/timeline", tenant.token)
        assertThat(JsonPath.read<List<String>>(timeline, "$[*].toStatus"))
            .containsExactly("DRAFT", "SUBMITTED", "REJECTED")
        assertThat(JsonPath.read<List<Any>>(timeline, "$[*].fromStatus").drop(1))
            .containsExactly("DRAFT", "SUBMITTED")
        assertThat(JsonPath.read<String>(timeline, "$[2].reason")).isEqualTo("Alamat di luar jangkauan")
        // Setiap kejadian punya pelakunya: "kapan dan oleh siapa" adalah gunanya riwayat ini.
        assertThat(JsonPath.read<String>(timeline, "$[0].actorId")).isNotBlank()
    }

    @Test
    fun `the operator queue finds an order by number, by name and by status`() {
        val tenant = newTenantAdmin("orq")
        val leadId = createLead(tenant, name = "Wahyu Pencarian")
        val created = createOrder(tenant, """"leadId":"$leadId"""")
        val orderId = JsonPath.read<String>(created, "$.id")
        val number = JsonPath.read<String>(created, "$.orderNumber")
        transition(tenant, orderId, "SUBMIT", 0)

        assertThat(JsonPath.read<List<String>>(get("/api/orders?query=$number", tenant.token), "$.content[*].id"))
            .containsExactly(orderId)
        val byName = get("/api/orders?query=Wahyu", tenant.token)
        assertThat(JsonPath.read<List<String>>(byName, "$.content[*].requesterName")).containsExactly("Wahyu Pencarian")
        assertThat(JsonPath.read<List<String>>(get("/api/orders?status=SUBMITTED", tenant.token), "$.content[*].id"))
            .containsExactly(orderId)
        assertThat(JsonPath.read<List<Any>>(get("/api/orders?status=FULFILLED", tenant.token), "$.content[*]")).isEmpty()
    }

    /**
     * Penanda portal HARUS ikut di baris antrean, bukan digali per pesanan.
     *
     * Sebelum ini `GET /api/orders` tak memulangkan penanda sama sekali, jadi konsol menjawab
     * "mana pesanan yang menunggu pelanggan?" dengan memuat riwayat SETIAP baris satu per satu —
     * 20 permintaan untuk satu halaman, dan tetap buta terhadap halaman lain. Tes ini menjaga dua
     * hal yang membuat akal-akalan itu bisa dibuang: penandanya ada di barisnya, DAN penyaringnya
     * bekerja lintas halaman di sisi server.
     */
    @Test
    fun `antrean memulangkan penanda portal dan bisa disaring menurutnya`() {
        val tenant = newTenantAdmin("orfl")
        val flaggedId = JsonPath.read<String>(createOrder(tenant, """"leadId":"${createLead(tenant, "Siti Tertahan")}""""), "$.id")
        val cleanId = JsonPath.read<String>(createOrder(tenant, """"leadId":"${createLead(tenant, "Andi Lancar")}""""), "$.id")
        // DRAFT tidak boleh diberi penanda portal — pesanan yang belum diajukan tak punya pelanggan
        // yang sedang menunggu apa pun. Jadi keduanya diajukan dulu.
        transition(tenant, flaggedId, "SUBMIT", 0)
        transition(tenant, cleanId, "SUBMIT", 0)

        val flagged = post(
            "/api/orders/$flaggedId/attention", tenant.token,
            """{"flag":"WAITING_CUSTOMER","reason":"Menunggu konfirmasi titik pemasangan",
                "operation":{"namespace":"order.attention","key":"${uniq()}","payloadHash":"${uniq()}"}}""",
            expected = 200,
        )
        // Pesanan yang dimuat ulang ikut membawa penandanya — layar detail tak perlu menebak.
        assertThat(JsonPath.read<String>(flagged, "$.portalFlag")).isEqualTo("WAITING_CUSTOMER")
        assertThat(JsonPath.read<String>(flagged, "$.portalFlagSource")).isEqualTo("OPERATOR")

        val all = get("/api/orders", tenant.token)
        assertThat(JsonPath.read<List<String>>(all, "$.content[?(@.id=='$flaggedId')].portalFlag"))
            .containsExactly("WAITING_CUSTOMER")
        assertThat(JsonPath.read<List<String>>(all, "$.content[?(@.id=='$flaggedId')].portalFlagReason"))
            .containsExactly("Menunggu konfirmasi titik pemasangan")

        assertThat(JsonPath.read<List<String>>(get("/api/orders?flagged=true", tenant.token), "$.content[*].id"))
            .containsExactly(flaggedId)
        assertThat(JsonPath.read<List<String>>(get("/api/orders?flagged=false", tenant.token), "$.content[*].id"))
            .containsExactly(cleanId)
        assertThat(
            JsonPath.read<List<String>>(get("/api/orders?portalFlag=WAITING_CUSTOMER", tenant.token), "$.content[*].id"),
        ).containsExactly(flaggedId)
        // Tanda yang TIDAK terpasang harus memulangkan kosong, bukan seluruh antrean. Penyaring
        // yang diam-diam tak terpasang paling gampang lolos justru lewat kasus ini.
        assertThat(
            JsonPath.read<List<Any>>(get("/api/orders?portalFlag=REQUIRES_ATTENTION", tenant.token), "$.content[*]"),
        ).isEmpty()
    }

    @Test
    fun `a prospect becomes a real customer exactly once`() {
        val tenant = newTenantAdmin("orpr")
        val planId = JsonPath.read<String>(
            post(
                "/api/catalog/plans", tenant.token,
                """{"name":"Paket ${uniq()}","description":null,"price":150000,"downMbps":20,"upMbps":10,"serviceTypes":["PPPOE"]}""",
            ),
            "$.id",
        )
        val leadId = createLead(tenant, name = "Calon Jadi Pelanggan")
        patch("/api/orders/leads/$leadId", tenant.token, """{"interestedPlanId":"$planId"}""")

        val first = post("/api/orders/leads/$leadId/promote", tenant.token, "{}", expected = 200)
        val customerId = JsonPath.read<String>(first, "$.customerId")
        assertThat(JsonPath.read<Boolean>(first, "$.alreadyConverted")).isFalse()

        // Tombol ditekan dua kali TIDAK melahirkan pelanggan kedua untuk orang yang sama.
        val second = post("/api/orders/leads/$leadId/promote", tenant.token, "{}", expected = 200)
        assertThat(JsonPath.read<String>(second, "$.customerId")).isEqualTo(customerId)
        assertThat(JsonPath.read<Boolean>(second, "$.alreadyConverted")).isTrue()

        val lead = get("/api/orders/leads/$leadId", tenant.token)
        assertThat(JsonPath.read<String>(lead, "$.status")).isEqualTo("CONVERTED")
        assertThat(JsonPath.read<String>(lead, "$.convertedCustomerId")).isEqualTo(customerId)
        assertThat(JsonPath.read<String>(get("/api/customers/$customerId", tenant.token), "$.name"))
            .isEqualTo("Calon Jadi Pelanggan")
    }
}

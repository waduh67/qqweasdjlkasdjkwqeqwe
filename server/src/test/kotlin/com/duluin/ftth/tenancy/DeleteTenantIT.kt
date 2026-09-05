package com.duluin.ftth.tenancy

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
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID

/**
 * Uji hapus tenant total lewat stack HTTP nyata (MockMvc) terhadap Postgres lokal
 * (ftth_test, role `ftth` NOSUPERUSER/NOBYPASSRLS → RLS benar-benar aktif).
 *
 * Alih-alih memeriksa segelintir tabel yang dipilih tangan, tes memakai katalog
 * `information_schema` yang sama seperti [TenantEraser]: setelah penghapusan, SETIAP
 * tabel ber-`tenant_id` wajib kosong untuk tenant terhapus — jadi tabel/module baru
 * ikut teruji otomatis. Sekaligus memverifikasi tenant lain sama sekali tak tersentuh
 * (regresi kebocoran lintas-tenant / scoping RLS).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DeleteTenantIT {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var onboarding: OnboardTenantUseCase

    @Autowired
    private lateinit var tenantApi: TenantApi

    @Autowired
    private lateinit var txManager: PlatformTransactionManager

    @PersistenceContext
    private lateinit var em: EntityManager

    private val tx by lazy { TransactionTemplate(txManager) }

    private val pass = "secret12345"

    private fun uniq() = UUID.randomUUID().toString().substring(0, 8)

    private fun login(slug: String, email: String, password: String = pass): String {
        val body = """{"tenantSlug":"$slug","email":"$email","password":"$password"}"""
        val json = mockMvc.perform(
            post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(body),
        ).andExpect(status().isOk).andReturn().response.contentAsString
        return JsonPath.read(json, "$.accessToken")
    }

    /** Semua BASE TABLE ber-`tenant_id` — cerminan sasaran [TenantEraser] (metadata, tak ter-RLS). */
    @Suppress("UNCHECKED_CAST")
    private fun tenantScopedTables(): List<String> =
        tx.execute {
            em.createNativeQuery(
                """
                SELECT c.table_name
                FROM information_schema.columns c
                JOIN information_schema.tables t
                  ON t.table_schema = c.table_schema AND t.table_name = c.table_name
                WHERE c.table_schema = 'public'
                  AND c.column_name = 'tenant_id'
                  AND t.table_type = 'BASE TABLE'
                """.trimIndent(),
            ).resultList.map { it as String }
        }!!

    /**
     * Hitung baris milik [tenantId] pada [table]. WAJIB di dalam [TenantContext.runAs] agar
     * koneksi ter-checkout dengan `app.tenant_id = tenantId`; kalau tidak, RLS `FORCE`
     * menyaring tabel ber-RLS ke nol dan hasilnya menyesatkan. `WHERE tenant_id` menutup
     * tabel non-RLS yang tak ikut disaring.
     */
    private fun countFor(table: String, tenantId: UUID): Long =
        TenantContext.runAs(tenantId) {
            tx.execute {
                (
                    em.createNativeQuery("""SELECT count(*) FROM "$table" WHERE tenant_id = :t""")
                        .setParameter("t", tenantId)
                        .singleResult as Number
                    ).toLong()
            }!!
        }

    private fun countCustomersWithLocationStatus(tenantId: UUID, locationStatus: String): Long =
        TenantContext.runAs(tenantId) {
            tx.execute {
                (
                    em.createNativeQuery("SELECT count(*) FROM customer WHERE tenant_id = :t AND location_status = :status")
                        .setParameter("t", tenantId)
                        .setParameter("status", locationStatus)
                        .singleResult as Number
                    ).toLong()
            }!!
        }

    /**
     * Semai satu ONU yang TERPASANG di ODP (odp_id & odp_port_number terisi) untuk [tenantId].
     * Ini kunci reproduksi bug: `DELETE FROM odp` akan men-`SET NULL` `onu.odp_id` (FK ON DELETE
     * SET NULL) padahal port masih terisi → melanggar CHECK `ck_onu_attachment` (SQLState 23514)
     * bila `odp` dihapus sebelum `onu`. Insert via SQL native di dalam konteks tenant (RLS).
     */
    private fun seedAttachedOnu(tenantId: UUID) {
        TenantContext.runAs(tenantId) {
            tx.executeWithoutResult {
                val customerId = UUID.randomUUID()
                val odpId = UUID.randomUUID()
                val suffix = uniq()
                em.createNativeQuery(
                    "INSERT INTO customer (id, tenant_id, code, name, address, location, location_status) " +
                        "VALUES (:id, :t, :code, 'ONU Test', 'Jl. Test', ST_SetSRID(ST_MakePoint(106.8, -6.2), 4326), 'LOCATED')",
                ).setParameter("id", customerId).setParameter("t", tenantId)
                    .setParameter("code", "CUST-$suffix").executeUpdate()
                em.createNativeQuery(
                    "INSERT INTO customer (id, tenant_id, code, name, address, location_status) " +
                        "VALUES (:id, :t, :code, 'Unlocated Test', 'Jl. Test', 'UNLOCATED')",
                ).setParameter("id", UUID.randomUUID()).setParameter("t", tenantId)
                    .setParameter("code", "UNLOC-$suffix").executeUpdate()
                em.createNativeQuery(
                    // `splitter_ratio` tak lagi ada di sini sejak V92 memindahkan rasio ke tabel
                    // `splitter` tersendiri (satu ODP boleh punya lebih dari satu kaki).
                    "INSERT INTO odp (id, tenant_id, code, name, location, capacity) " +
                        "VALUES (:id, :t, :code, 'ODP Test', ST_SetSRID(ST_MakePoint(106.8, -6.2), 4326), 8)",
                ).setParameter("id", odpId).setParameter("t", tenantId)
                    .setParameter("code", "ODP-$suffix").executeUpdate()
                em.createNativeQuery(
                    "INSERT INTO onu (id, tenant_id, customer_id, odp_id, odp_port_number, serial_number) " +
                        "VALUES (:id, :t, :cust, :odp, 1, :sn)",
                ).setParameter("id", UUID.randomUUID()).setParameter("t", tenantId)
                    .setParameter("cust", customerId).setParameter("odp", odpId)
                    .setParameter("sn", "SN-$suffix").executeUpdate()
            }
        }
    }

    @Test
    fun `hapus tenant membersihkan seluruh datanya tanpa menyentuh tenant lain`() {
        val victimSlug = "del${uniq()}"
        val bystanderSlug = "keep${uniq()}"
        val victimAdmin = "admin@$victimSlug.test"
        val bystanderAdmin = "admin@$bystanderSlug.test"
        val victim = onboarding.onboard(
            OnboardTenantCommand(victimSlug, "Victim ISP", victimAdmin, "Admin V", pass),
        ).tenant
        val bystander = onboarding.onboard(
            OnboardTenantCommand(bystanderSlug, "Bystander ISP", bystanderAdmin, "Admin B", pass),
        ).tenant

        val tables = tenantScopedTables()

        // Prasyarat: onboarding menebar data lintas module (app_user, role, user_role,
        // user_directory, audit_log, tenant_subscription, …) untuk KEDUA tenant.
        assertThat(tables.sumOf { countFor(it, victim.id) })
            .`as`("tenant korban harus punya data sebelum dihapus").isGreaterThan(0)
        val bystanderBefore = tables.associateWith { countFor(it, bystander.id) }
        assertThat(bystanderBefore.values.sum())
            .`as`("tenant lain harus punya data sebelum penghapusan").isGreaterThan(0)

        // Hapus korban lewat endpoint platform admin nyata.
        val root = login("platform", "root@ftth.local", "rootadmin123")
        mockMvc.perform(
            delete("/api/platform/tenants/${victim.id}").header("Authorization", "Bearer $root"),
        ).andExpect(status().isNoContent)

        // Tenant lenyap dari platform.
        mockMvc.perform(
            get("/api/platform/tenants/${victim.id}").header("Authorization", "Bearer $root"),
        ).andExpect(status().isNotFound)

        // SETIAP tabel ber-tenant_id kosong untuk tenant terhapus.
        tables.forEach { table ->
            assertThat(countFor(table, victim.id))
                .`as`("tabel %s harus kosong untuk tenant terhapus", table).isZero
        }

        // Tenant lain utuh persis seperti sebelumnya — tak ada kebocoran lintas-tenant.
        tables.forEach { table ->
            assertThat(countFor(table, bystander.id))
                .`as`("tabel %s milik tenant lain harus tetap utuh", table)
                .isEqualTo(bystanderBefore[table])
        }
    }

    @Test
    fun `hapus tenant dengan ONU terpasang di ODP tetap sukses (regresi ck_onu_attachment)`() {
        val slug = "onu${uniq()}"
        val admin = "admin@$slug.test"
        val victim = onboarding.onboard(
            OnboardTenantCommand(slug, "ONU ISP", admin, "Admin", pass),
        ).tenant
        seedAttachedOnu(victim.id)

        // Prasyarat reproduksi: ONU benar-benar terpasang (odp_id terisi). Menghapus `odp`
        // sebelum `onu` akan men-SET NULL odp_id & melanggar ck_onu_attachment (dulu → 500).
        assertThat(countFor("customer", victim.id)).isEqualTo(2)
        assertThat(countCustomersWithLocationStatus(victim.id, "LOCATED")).isEqualTo(1)
        assertThat(countCustomersWithLocationStatus(victim.id, "UNLOCATED")).isEqualTo(1)
        assertThat(countFor("odp", victim.id)).isEqualTo(1)
        assertThat(countFor("onu", victim.id)).isEqualTo(1)

        val root = login("platform", "root@ftth.local", "rootadmin123")
        mockMvc.perform(
            delete("/api/platform/tenants/${victim.id}").header("Authorization", "Bearer $root"),
        ).andExpect(status().isNoContent)

        assertThat(countFor("onu", victim.id)).isZero
        assertThat(countFor("odp", victim.id)).isZero
        assertThat(countFor("customer", victim.id)).isZero
    }

    @Test
    fun `tenant platform tidak bisa dihapus`() {
        val root = login("platform", "root@ftth.local", "rootadmin123")
        val platformId = tenantApi.platformTenantId()

        mockMvc.perform(
            delete("/api/platform/tenants/$platformId").header("Authorization", "Bearer $root"),
        ).andExpect(status().isBadRequest)

        // Data platform tetap ada (mis. akun root masih bisa login setelah penolakan).
        assertThat(countFor("app_user", platformId)).isGreaterThan(0)
    }
}

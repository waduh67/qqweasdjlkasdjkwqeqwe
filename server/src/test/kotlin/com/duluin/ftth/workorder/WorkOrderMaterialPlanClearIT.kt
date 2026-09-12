package com.duluin.ftth.workorder

import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.iam.application.port.inbound.OnboardTenantCommand
import com.duluin.ftth.iam.application.port.inbound.OnboardTenantUseCase
import com.duluin.ftth.tenancy.TenantApi
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.util.Locale
import java.util.UUID

/**
 * Mengosongkan rencana material sebuah WO lewat `PUT .../materials` dengan `clear = true`.
 *
 * Yang dijaga di sini adalah satu perbedaan yang gampang sekali dihapus orang saat merapikan
 * kode: `lines` kosong dan `clear` BUKAN hal yang sama. `lines` kosong sudah punya arti sejak
 * awal — "pakai BOM apa adanya", yaitu jalur pra-isi yang dipakai dispatcher setiap kali
 * membuka WO baru. Kalau suatu saat `clear` "disederhanakan" menjadi sekadar "lines kosong",
 * setiap penekanan tombol pra-isi berubah jadi pengosongan rencana, dan setiap pengosongan
 * berubah jadi pengisian BOM — dua perintah yang saling menukar hasil tanpa satu pun error.
 *
 * Bagian yang paling mahal kalau salah adalah PENOLAKANNYA. Rencana yang barangnya sudah
 * telanjur keluar gudang tidak boleh dicoret: barangnya nyata dan ada di tangan teknisi.
 * Penjaga itu berjalan per baris di tengah perulangan penghapusan, jadi pengosongan yang
 * ditolak di baris terakhir harus TETAP meninggalkan seluruh baris sebelumnya utuh. Kalau
 * rollback-nya bocor, hasilnya adalah 409 yang terlihat aman di layar padahal separuh rencana
 * sudah lenyap — dan barang yang hilang dari rencana itu tetap ada di van teknisi tanpa satu
 * pun baris yang menuntut pertanggungjawabannya.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class WorkOrderMaterialPlanClearIT {

    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var onboarding: OnboardTenantUseCase
    @Autowired private lateinit var tenants: TenantApi
    @Autowired private lateinit var txManager: PlatformTransactionManager

    @PersistenceContext private lateinit var em: EntityManager

    private val pass = "secret12345"
    private val custodian = UUID.randomUUID()

    // ------------------------------------------------------------------ HTTP

    private fun uniq() = UUID.randomUUID().toString().replace("-", "").substring(0, 8)

    private fun post(url: String, token: String, body: String, expected: Int = 201): String =
        mockMvc.perform(
            post(url).header("Authorization", "Bearer $token")
                .contentType(MediaType.APPLICATION_JSON).content(body),
        ).andReturn().response.let {
            assertThat(it.status).describedAs("POST $url -> ${it.contentAsString}").isEqualTo(expected)
            it.contentAsString
        }

    private fun put(url: String, token: String, body: String, expected: Int = 200): String =
        mockMvc.perform(
            put(url).header("Authorization", "Bearer $token")
                .contentType(MediaType.APPLICATION_JSON).content(body),
        ).andReturn().response.let {
            assertThat(it.status).describedAs("PUT $url -> ${it.contentAsString}").isEqualTo(expected)
            it.contentAsString
        }

    private fun getJson(url: String, token: String, expected: Int = 200): String =
        mockMvc.perform(get(url).header("Authorization", "Bearer $token")).andReturn().response.let {
            assertThat(it.status).describedAs("GET $url -> ${it.contentAsString}").isEqualTo(expected)
            it.contentAsString
        }

    private fun id(json: String): String = JsonPath.read(json, "$.id")

    /** Pesan RFC-7807 yang benar-benar dibaca dispatcher di layar. */
    private fun detail(problem: String): String = JsonPath.read(problem, "$.detail")

    private fun rows(json: String): List<Map<String, Any>> = JsonPath.read(json, "$[*]")

    private fun login(slug: String, email: String): String = JsonPath.read(
        mockMvc.perform(
            post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("""{"tenantSlug":"$slug","email":"$email","password":"$pass"}"""),
        ).andExpect(status().isOk).andReturn().response.contentAsString,
        "$.accessToken",
    )

    private fun slugOf(token: String): String = JsonPath.read(getJson("/api/me", token), "$.tenantSlug")

    private fun tenantIdOf(token: String): UUID = tenants.findBySlug(slugOf(token))!!.id

    private fun adminToken(prefix: String): String {
        val slug = "$prefix${uniq()}"
        val admin = "admin@$slug.test"
        onboarding.onboard(OnboardTenantCommand(slug, "Tenant $slug", admin, "Admin", pass))
        return login(slug, admin)
    }

    /**
     * Peran BARU berisi tepat [codes], dipakai seorang pengguna baru, kembalikan tokennya.
     *
     * Perannya dirakit sendiri, BUKAN hasil memangkas peran sistem "Tenant Admin":
     * `AdminProvisioner` menyinkronkan ulang peran sistem ke katalog izin penuh saat bootstrap,
     * jadi pemangkasan apa pun akan pulih dengan sendirinya dan tes izin ini akan lulus tanpa
     * pernah benar-benar menguji penjaganya.
     */
    private fun tokenWithPermissions(adminToken: String, label: String, vararg codes: String): String {
        val slug = slugOf(adminToken)
        val permissions = getJson("/api/permissions", adminToken)
        val ids = codes.joinToString(",") { code ->
            "\"" + JsonPath.read<List<String>>(permissions, "$[?(@.code=='$code')].id").first() + "\""
        }
        val roleId = id(post("/api/roles", adminToken, """{"name":"$label ${uniq()}","permissionIds":[$ids]}"""))
        val email = "${label.lowercase(Locale.ROOT).replace(' ', '-')}${uniq()}@$slug.test"
        post("/api/users", adminToken, """{"email":"$email","name":"$label","password":"$pass","roleIds":["$roleId"]}""")
        return login(slug, email)
    }

    // ------------------------------------------------------------ Basis data

    /**
     * Berapa baris rencana yang BENAR-BENAR tersisa di tabel, dilihat dari dalam tenant.
     *
     * Read model bukan bukti penghapusan: pemulangan daftar kosong bisa juga berarti barisnya
     * masih hidup tapi tersaring di jalur baca. Selisih itu baru terasa jauh kemudian — saat
     * saga persetujuan membaca tabel yang sama dan memotong saldo untuk baris yang menurut
     * layar sudah tidak ada.
     *
     * Native query lewat [EntityManager], bukan `JdbcTemplate`: RLS Postgres di repo ini
     * memagari baris dengan GUC `app.tenant_id`, dan koneksi yang diambil di luar
     * [TenantContext] mengembalikan NOL baris tanpa satu pun error — angka nol yang terlihat
     * persis seperti "sudah terhapus".
     */
    private fun plannedRowCount(token: String, workOrderId: String): Long {
        val tenantId = tenantIdOf(token)
        return TenantContext.runAs(tenantId) {
            TransactionTemplate(txManager).execute {
                (
                    em.createNativeQuery("SELECT count(*) FROM work_order_material WHERE work_order_id = :wo")
                        .setParameter("wo", UUID.fromString(workOrderId))
                        .singleResult as Number
                    ).toLong()
            }!!
        }
    }

    // -------------------------------------------------------------- Fixtures

    private fun location(token: String, code: String, kind: String): String =
        id(post("/api/inventory/locations", token, """{"code":"$code","kind":"$kind"}"""))

    private fun item(token: String, code: String, category: String, unit: String, serialized: Boolean): String = id(
        post(
            "/api/inventory/item-master", token,
            """{"code":"$code","name":"Barang $code","category":"$category","unit":"$unit","serialized":$serialized}""",
        ),
    )

    private fun balance(token: String, itemId: String, locationId: String, status: String): Int {
        val found: List<Map<String, Any>> = JsonPath.read(getJson("/api/inventory/balances?itemId=$itemId", token), "$")
        return found.filter { it["locationId"] == locationId && it["status"] == status }
            .sumOf { (it["quantity"] as Number).toInt() }
    }

    private fun newTechnician(token: String): String {
        val roles = getJson("/api/roles", token)
        val names = JsonPath.read<List<String>>(roles, "$[*].name")
        val ids = JsonPath.read<List<String>>(roles, "$[*].id")
        val roleId = ids[names.indexOf("Teknisi")]
        return id(
            post(
                "/api/users", token,
                """{"email":"tech-${uniq()}@x.test","name":"Teknisi Clear","password":"$pass","roleIds":["$roleId"]}""",
            ),
        )
    }

    /** Material WAJIB punya pelanggan (lihat `WorkOrderService.requireMaterialCustomer`). */
    private fun customerWithSubscription(token: String): Pair<String, String> {
        val s = uniq().uppercase()
        val planId = id(
            post(
                "/api/catalog/plans", token,
                """{"name":"Paket ${uniq()}","description":null,"price":150000,"downMbps":20,"upMbps":10,"serviceTypes":["PPPOE"]}""",
            ),
        )
        val customer = post(
            "/api/customers", token,
            """{"code":"C-$s","name":"Pelanggan $s","address":"Jl. Uji",
                "location":{"longitude":106.99,"latitude":-6.24},"planId":"$planId"}""",
        )
        return id(customer) to JsonPath.read<String>(customer, "$.subscription.id")
    }

    /**
     * Satu tenant siap pakai: gudang berisi stok, BOM PSB, dan satu WO PSB yang sudah dimulai.
     *
     * WO-nya SENGAJA berhenti di IN_PROGRESS dan tidak pernah diselesaikan atau disetujui.
     * `WorkOrderService.requireMaterialWritable` menolak SETIAP tulisan material begitu WO-nya
     * APPROVED dengan 409 juga — kalau fixture ini menyetujui WO-nya, seluruh tes di bawah akan
     * tetap hijau lewat penjaga yang sama sekali berbeda dan tidak satu pun menguji `clear`.
     */
    private data class Fixture(
        val token: String,
        val workOrderId: String,
        val warehouse: String,
        val van: String,
        val technicianId: String,
        val ont: String,
        val cable: String,
    )

    private fun fixture(): Fixture {
        val token = adminToken("wo-clear")
        val warehouse = location(token, "WH-${uniq()}", "WAREHOUSE")
        val van = location(token, "VAN-${uniq()}", "VEHICLE")
        val ont = item(token, "ONT-${uniq()}", "ONT", "PCS", serialized = true)
        val cable = item(token, "DC-${uniq()}", "DROPCORE", "METER", serialized = false)

        post(
            "/api/inventory/receipts", token,
            """{"locationId":"$warehouse","custodianId":"$custodian","reason":"kiriman kabel",
                "operationKey":"grn-${uniq()}","payloadHash":"grn-hash",
                "lines":[{"itemId":"$cable","quantity":200}]}""",
        )
        post(
            "/api/inventory/serialized/bulk", token,
            """{"itemId":"$ont","locationId":"$warehouse","custodianId":"$custodian","reason":"kiriman ONT",
                "operationKey":"sn-${uniq()}","payloadHash":"sn-hash",
                "serials":[{"serialNumber":"SN-${uniq().uppercase()}"}]}""",
        )
        // BOM jenis PSB: 1 ONT + 80 meter dropcore. Inilah daftar yang harus muncul kembali
        // setiap kali `lines` dikirim kosong TANPA `clear`.
        put(
            "/api/inventory/material-templates/PSB", token,
            """{"lines":[{"itemId":"$ont","plannedQuantity":1},{"itemId":"$cable","plannedQuantity":80}]}""",
        )

        val (customerId, subscriptionId) = customerWithSubscription(token)
        val workOrderId = id(
            post(
                "/api/work-orders", token,
                """{"type":"PSB","title":"Pasang baru","customerId":"$customerId","subscriptionId":"$subscriptionId"}""",
            ),
        )
        val technicianId = newTechnician(token)
        post("/api/work-orders/$workOrderId/assign", token, """{"technicianIds":["$technicianId"]}""", 200)
        post("/api/work-orders/$workOrderId/start", token, "", 200)
        return Fixture(token, workOrderId, warehouse, van, technicianId, ont, cable)
    }

    private fun issueCable(f: Fixture, quantity: Int) = post(
        "/api/work-orders/${f.workOrderId}/materials/issues", f.token,
        """{"fromLocationId":"${f.warehouse}","custodianId":"$custodian","technicianId":"${f.technicianId}",
            "technicianLocationId":"${f.van}","reason":"bekal PSB",
            "operationKey":"iss-${uniq()}","payloadHash":"iss-hash",
            "lines":[{"itemId":"${f.cable}","quantity":$quantity}]}""",
        expected = 200,
    )

    // ------------------------------------------------------------------ Tes

    /**
     * Pengosongan biasa: rencana yang belum keluar gudang benar-benar hilang dari tabel.
     *
     * Daftar kosong di respons saja tidak cukup dibuktikan. Rencana material dibaca lagi oleh
     * jalur pengeluaran barang dan oleh saga persetujuan langsung dari tabelnya, jadi baris
     * yang cuma tersaring di read model akan tetap dianggap "harus dibawa" oleh setiap
     * pembaca lain — dan dispatcher yang melihat layar kosong tidak punya cara memperbaikinya.
     */
    @Test
    fun `clear mengosongkan rencana yang belum keluar gudang dan barisnya benar-benar terhapus`() {
        val f = fixture()
        val planned = put("/api/work-orders/${f.workOrderId}/materials", f.token, """{"lines":[]}""")
        assertThat(rows(planned)).hasSize(2)
        assertThat(plannedRowCount(f.token, f.workOrderId)).isEqualTo(2)

        val cleared = put("/api/work-orders/${f.workOrderId}/materials", f.token, """{"lines":[],"clear":true}""")
        assertThat(rows(cleared)).isEmpty()

        assertThat(rows(getJson("/api/work-orders/${f.workOrderId}/materials", f.token))).isEmpty()
        assertThat(plannedRowCount(f.token, f.workOrderId)).isZero()

        // Dan pembacanya yang lain setuju: barang yang tidak lagi direncanakan tidak bisa
        // dikeluarkan gudang. Kalau barisnya masih hidup diam-diam, pengeluaran ini lolos.
        post(
            "/api/work-orders/${f.workOrderId}/materials/issues", f.token,
            """{"fromLocationId":"${f.warehouse}","custodianId":"$custodian","technicianId":"${f.technicianId}",
                "technicianLocationId":"${f.van}","reason":"bekal PSB",
                "operationKey":"iss-${uniq()}","payloadHash":"iss-hash",
                "lines":[{"itemId":"${f.cable}","quantity":10}]}""",
            expected = 400,
        )
    }

    /**
     * INTI tes ini: pengosongan yang ditolak harus meninggalkan rencana UTUH.
     *
     * Kabelnya sudah keluar gudang dan ada di van teknisi; ONT-nya belum. `clear` membuat
     * KEDUA baris jadi yatim sekaligus, dan penjaganya berjalan di dalam perulangan — jadi
     * baris ONT sempat dihapus sebelum baris kabel melempar 409. Tanpa rollback yang benar,
     * dispatcher melihat pesan "tidak bisa dicoret" lalu menemukan ONT-nya raib dari rencana,
     * dan tidak ada apa pun di layar yang menjelaskan kenapa satu baris hilang di operasi
     * yang katanya gagal.
     */
    @Test
    fun `clear ditolak utuh saat ada baris yang sudah dikeluarkan gudang`() {
        val f = fixture()
        // Kedua baris SENGAJA dilahirkan di dua permintaan terpisah supaya urutannya pasti:
        // rencana dibaca `ORDER BY created_at ASC`, jadi baris ONT (yang boleh dihapus) selalu
        // disentuh lebih dulu daripada baris kabel (yang melempar). Kalau keduanya lahir dalam
        // satu permintaan, urutannya bergantung pada stempel waktu yang bisa sama persis — dan
        // pada urutan yang kebetulan menaruh kabel di depan, 409 keluar SEBELUM satu baris pun
        // dihapus, sehingga tes ini lulus tanpa pernah menyentuh rollback yang justru diujinya.
        put("/api/work-orders/${f.workOrderId}/materials", f.token, """{"lines":[{"itemId":"${f.ont}","quantity":1}]}""")
        put(
            "/api/work-orders/${f.workOrderId}/materials", f.token,
            """{"lines":[{"itemId":"${f.ont}","quantity":1},{"itemId":"${f.cable}","quantity":80}]}""",
        )
        issueCable(f, 80)

        val before = rows(getJson("/api/work-orders/${f.workOrderId}/materials", f.token))
            .associate { it["itemId"] to (it["plannedQuantity"] to it["issuedQuantity"]) }
        assertThat(before).hasSize(2)
        // Urutan itu ikut ditegakkan di sini: kalau suatu saat berubah, tes ini harus berteriak,
        // bukan diam-diam berhenti menguji penghapusan-lalu-rollback.
        assertThat(before.keys.first()).isEqualTo(f.ont)

        val rejected = put("/api/work-orders/${f.workOrderId}/materials", f.token, """{"clear":true}""", expected = 409)
        assertThat(detail(rejected)).contains("tidak bisa dicoret dari rencana")

        val after = rows(getJson("/api/work-orders/${f.workOrderId}/materials", f.token))
            .associate { it["itemId"] to (it["plannedQuantity"] to it["issuedQuantity"]) }
        assertThat(after).isEqualTo(before)
        assertThat(plannedRowCount(f.token, f.workOrderId)).isEqualTo(2)
        // Saldo van pun tidak tersentuh: penolakan rencana tidak boleh menyeret stok apa pun.
        assertThat(balance(f.token, f.cable, f.van, "ISSUED")).isEqualTo(80)
    }

    /**
     * `clear` menang atas `lines` — bukan digabung, bukan diabaikan.
     *
     * Kalau `lines` sempat ikut tersimpan, hasilnya adalah rencana berisi barang yang justru
     * ingin dihapus dispatcher, dengan HTTP 200 yang meyakinkan. Klien yang mengirim keduanya
     * bukan hal mengada-ada: layar yang sama menahan draf `lines` di state-nya dan tombol
     * "kosongkan" hanya menambahkan satu bendera.
     */
    @Test
    fun `clear mengabaikan lines yang ikut dikirim`() {
        val f = fixture()
        put("/api/work-orders/${f.workOrderId}/materials", f.token, """{"lines":[]}""")

        val cleared = put(
            "/api/work-orders/${f.workOrderId}/materials", f.token,
            """{"lines":[{"itemId":"${f.cable}","quantity":25},{"itemId":"${f.ont}","quantity":1}],"clear":true}""",
        )
        assertThat(rows(cleared)).isEmpty()
        assertThat(rows(getJson("/api/work-orders/${f.workOrderId}/materials", f.token))).isEmpty()
        assertThat(plannedRowCount(f.token, f.workOrderId)).isZero()
    }

    /**
     * REGRESI UTAMA: `lines` kosong TANPA `clear` tetap berarti "pakai BOM apa adanya".
     *
     * Inilah tes yang harus merah duluan kalau suatu saat `clear` disederhanakan menjadi
     * "lines kosong". Kedua bentuk itu dikirim oleh tombol yang berbeda di layar yang sama:
     * satu mempra-isi rencana dari BOM, satu lagi mengosongkannya. Menyatukan keduanya membuat
     * dispatcher yang menekan "pakai BOM" justru menghapus rencananya — dan karena responsnya
     * 200 dengan daftar kosong, ia akan mengira BOM jenis WO itu yang belum diisi.
     *
     * Pengujiannya dilakukan SESUDAH pengosongan, bukan pada WO perawan, supaya dua arti itu
     * benar-benar diadu di WO yang sama: dari nol, `lines: []` harus MENGISI, bukan bertahan
     * kosong mengikuti keadaan sebelumnya.
     */
    @Test
    fun `lines kosong tanpa clear tetap berarti pakai BOM apa adanya`() {
        val f = fixture()

        val prefilled = put("/api/work-orders/${f.workOrderId}/materials", f.token, """{"lines":[]}""")
        assertThat(rows(prefilled).associate { it["itemId"] to it["plannedQuantity"] })
            .isEqualTo(mapOf(f.ont to 1, f.cable to 80))

        put("/api/work-orders/${f.workOrderId}/materials", f.token, """{"clear":true}""")
        assertThat(plannedRowCount(f.token, f.workOrderId)).isZero()

        val refilled = put("/api/work-orders/${f.workOrderId}/materials", f.token, """{"lines":[]}""")
        assertThat(rows(refilled).associate { it["itemId"] to it["plannedQuantity"] })
            .isEqualTo(mapOf(f.ont to 1, f.cable to 80))
        // `templateQuantity` ikut terisi — buktinya angkanya memang datang dari BOM, bukan
        // kebetulan tersisa dari rencana sebelumnya.
        assertThat(rows(refilled).map { it["templateQuantity"] }).containsExactlyInAnyOrder(1, 80)
        assertThat(plannedRowCount(f.token, f.workOrderId)).isEqualTo(2)
    }

    /**
     * Mengosongkan tidak boleh membuat WO-nya buntu selamanya.
     *
     * Pengosongan dipakai justru saat rencananya salah total dan harus disusun dari nol. Kalau
     * setelah `clear` rencananya tidak bisa diisi lagi — atau bisa diisi tapi barisnya tidak
     * benar-benar hidup — satu-satunya jalan keluar teknisi adalah membuat WO baru, dan WO lama
     * yang sudah berisi bukti pengerjaan ikut dibuang bersamanya.
     */
    @Test
    fun `rencana bisa disusun ulang dari nol sesudah dikosongkan`() {
        val f = fixture()
        put("/api/work-orders/${f.workOrderId}/materials", f.token, """{"lines":[]}""")
        put("/api/work-orders/${f.workOrderId}/materials", f.token, """{"clear":true}""")
        assertThat(plannedRowCount(f.token, f.workOrderId)).isZero()

        val replanned = put(
            "/api/work-orders/${f.workOrderId}/materials", f.token,
            """{"lines":[{"itemId":"${f.cable}","quantity":120}]}""",
        )
        assertThat(rows(replanned).associate { it["itemId"] to it["plannedQuantity"] })
            .isEqualTo(mapOf(f.cable to 120))
        assertThat(rows(getJson("/api/work-orders/${f.workOrderId}/materials", f.token))).hasSize(1)
        assertThat(plannedRowCount(f.token, f.workOrderId)).isEqualTo(1)

        // Rencana barunya bukan sekadar baris di layar: gudang benar-benar mau melayaninya,
        // dengan batas yang mengikuti angka BARU (120), bukan angka BOM yang sudah dibuang.
        issueCable(f, 120)
        assertThat(balance(f.token, f.cable, f.van, "ISSUED")).isEqualTo(120)
    }

    /**
     * Pengosongan adalah operasi TULIS, dan dijaga izin tulis.
     *
     * `workorder.material.view` membuka layar rencana bagi siapa pun yang perlu membacanya —
     * petugas gudang, penyelia, admin area. Kalau `clear` ikut lolos dengan izin baca, setiap
     * pemegang layar itu bisa menghapus seluruh rencana material sebuah WO hanya dengan satu
     * bendera di request, dan jejaknya nihil karena barisnya memang dihapus.
     */
    @Test
    fun `clear ditolak untuk pemegang izin baca material saja`() {
        val f = fixture()
        put("/api/work-orders/${f.workOrderId}/materials", f.token, """{"lines":[]}""")
        val readOnly = tokenWithPermissions(f.token, "Pembaca Material", "workorder.material.view", "workorder.order.view")

        put("/api/work-orders/${f.workOrderId}/materials", readOnly, """{"clear":true}""", expected = 403)
        assertThat(plannedRowCount(f.token, f.workOrderId)).isEqualTo(2)
    }
}

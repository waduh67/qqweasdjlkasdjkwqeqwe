package com.duluin.ftth.inventory

import com.duluin.ftth.iam.application.port.inbound.OnboardTenantCommand
import com.duluin.ftth.iam.application.port.inbound.OnboardTenantUseCase
import com.jayway.jsonpath.JsonPath
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
import java.util.UUID

/**
 * Read model gudang WAJIB membawa namanya sendiri.
 *
 * Sebelum ini setiap baris hanya berisi `itemCode`/`locationCode` dan UUID orang, jadi tiap layar
 * web menggabungkan sendiri terhadap `/item-master` dan `/api/users`. Petugas gudang yang tidak
 * punya `inventory.item.view` atau `iam.user.view` kena 403 pada penggabungan itu lalu membaca
 * kode dan UUID telanjang — tepat pada layar yang jadi pekerjaannya sehari-hari. Yang diuji di
 * sini adalah bahwa nama itu sekarang datang dari server, dan bahwa ia tetap benar di kasus yang
 * paling gampang salah: barang yang sudah dipensiunkan, pemegang custody yang bukan orang, dan
 * id milik tenant sebelah.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class InventoryReadModelNamesIT {

    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var onboarding: OnboardTenantUseCase

    private val pass = "secret12345"

    private fun uniq() = UUID.randomUUID().toString().replace("-", "").substring(0, 8)

    private fun login(slug: String, email: String): String = JsonPath.read(
        mockMvc.perform(
            post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("""{"tenantSlug":"$slug","email":"$email","password":"$pass"}"""),
        ).andReturn().response.contentAsString,
        "$.accessToken",
    )

    /** Tenant baru plus token adminnya. Nama admin dipakai untuk memeriksa `actorName` di ledger. */
    private fun adminToken(prefix: String, adminName: String = "Admin"): String {
        val slug = "$prefix${uniq()}"
        val admin = "admin@$slug.test"
        onboarding.onboard(OnboardTenantCommand(slug, "Tenant $slug", admin, adminName, pass))
        return login(slug, admin)
    }

    private fun post(url: String, token: String, body: String, expected: Int = 201): String =
        mockMvc.perform(
            post(url).header("Authorization", "Bearer $token")
                .contentType(MediaType.APPLICATION_JSON).content(body),
        ).andReturn().response.let {
            assertThat(it.status).describedAs("POST $url -> ${it.contentAsString}").isEqualTo(expected)
            it.contentAsString
        }

    private fun getJson(url: String, token: String): String =
        mockMvc.perform(get(url).header("Authorization", "Bearer $token")).andReturn().response.let {
            assertThat(it.status).describedAs("GET $url -> ${it.contentAsString}").isEqualTo(200)
            it.contentAsString
        }

    private fun id(json: String): String = JsonPath.read(json, "$.id")

    private fun location(token: String, code: String, kind: String): String =
        id(post("/api/inventory/locations", token, """{"code":"$code","kind":"$kind"}"""))

    private fun item(token: String, code: String, name: String, serialized: Boolean = false): String = id(
        post(
            "/api/inventory/item-master", token,
            """{"code":"$code","name":"$name","category":"ONT","unit":"PCS","serialized":$serialized}""",
        ),
    )

    private fun newUser(token: String, name: String, roleName: String): String {
        val roles = getJson("/api/roles", token)
        val names = JsonPath.read<List<String>>(roles, "$[*].name")
        val ids = JsonPath.read<List<String>>(roles, "$[*].id")
        val roleIndex = names.indexOf(roleName).takeIf { it >= 0 } ?: 0
        return id(
            post(
                "/api/users", token,
                """{"email":"u-${uniq()}@x.test","name":"$name","password":"$pass","roleIds":["${ids[roleIndex]}"]}""",
            ),
        )
    }

    private fun rows(url: String, token: String): List<Map<String, Any?>> = JsonPath.read(getJson(url, token), "$")

    /**
     * `custodianId` gudang SENGAJA diisi id lokasinya sendiri.
     *
     * Begitulah bentuk custody gudang: `custodyOwnerKind = WAREHOUSE` dan id-nya menunjuk rak,
     * bukan orang. Kalau read model memaksakan direktori pengguna untuk kolom ini (seperti yang
     * dilakukan klien sebelum perbaikan), seluruh baris gudang tampil sebagai UUID.
     */
    private fun receive(token: String, warehouse: String, itemId: String, quantity: Int, key: String = uniq()) =
        post(
            "/api/inventory/receipts", token,
            """{"locationId":"$warehouse","custodianId":"$warehouse","reason":"kiriman awal",
                "operationKey":"grn-$key","payloadHash":"grn-hash-$key",
                "lines":[{"itemId":"$itemId","quantity":$quantity}]}""",
        )

    // ------------------------------------------------------------------------------------

    @Test
    fun `saldo membawa nama barang, jenis lokasi, dan pemegang custody gudang sebagai kode lokasi`() {
        val token = adminToken("inv-nm-bal")
        val whCode = "WH-${uniq()}"
        val warehouse = location(token, whCode, "WAREHOUSE")
        val kabel = item(token, "KBL-${uniq()}", "Kabel Dropcore 80m")
        receive(token, warehouse, kabel, 40)

        val row = rows("/api/inventory/balances?itemId=$kabel", token).single()
        assertThat(row["itemName"]).isEqualTo("Kabel Dropcore 80m")
        assertThat(row["locationKind"]).isEqualTo("WAREHOUSE")
        // Inti perbaikannya: pemegang custody gudang muncul sebagai KODE LOKASI, bukan UUID.
        assertThat(row["custodyOwnerKind"]).isEqualTo("WAREHOUSE")
        assertThat(row["custodyOwnerName"]).isEqualTo(whCode.uppercase())
        assertThat(row["custodyOwnerName"]).isNotEqualTo(warehouse)
    }

    @Test
    fun `van stock membawa nama teknisi sungguhan, bukan UUID`() {
        val token = adminToken("inv-nm-van")
        val warehouse = location(token, "WH-${uniq()}", "WAREHOUSE")
        val van = location(token, "VAN-${uniq()}", "VEHICLE")
        val kabel = item(token, "KBL-${uniq()}", "Kabel Feeder 12 core")
        val technicianId = newUser(token, "Budi Teknisi", "Teknisi")
        receive(token, warehouse, kabel, 30)

        post(
            "/api/inventory/issues", token,
            """{"fromLocationId":"$warehouse","custodianId":"$warehouse","technicianId":"$technicianId",
                "technicianLocationId":"$van","reason":"bekal harian",
                "operationKey":"iss-${uniq()}","payloadHash":"iss-hash",
                "lines":[{"itemId":"$kabel","quantity":10}]}""",
        )

        val vans = rows("/api/inventory/van-stock?technicianId=$technicianId", token)
        assertThat(vans).hasSize(1)
        assertThat(vans.single()["technicianName"]).isEqualTo("Budi Teknisi")
        @Suppress("UNCHECKED_CAST")
        val line = (vans.single()["lines"] as List<Map<String, Any?>>).single()
        assertThat(line["itemName"]).isEqualTo("Kabel Feeder 12 core")
        assertThat(line["locationKind"]).isEqualTo("VEHICLE")
    }

    @Test
    fun `ledger membawa nama pelaku dan nama barang tiap leg`() {
        val token = adminToken("inv-nm-led", adminName = "Sri Gudang")
        val whCode = "WH-${uniq()}"
        val warehouse = location(token, whCode, "WAREHOUSE")
        val kabel = item(token, "KBL-${uniq()}", "Patchcord SC-UPC 3m")
        receive(token, warehouse, kabel, 12)

        val page = getJson("/api/inventory/ledger?page=0&size=20", token)
        assertThat(JsonPath.read<String>(page, "$.content[0].actorName")).isEqualTo("Sri Gudang")
        assertThat(JsonPath.read<String>(page, "$.content[0].legs[0].itemName")).isEqualTo("Patchcord SC-UPC 3m")
        assertThat(JsonPath.read<String>(page, "$.content[0].legs[0].locationKind")).isEqualTo("WAREHOUSE")
        // Leg gudang: pemegang custody-nya rak, jadi yang tampil kode lokasi.
        assertThat(JsonPath.read<String>(page, "$.content[0].legs[0].custodyOwnerName")).isEqualTo(whCode.uppercase())
    }

    @Test
    fun `laporan selisih membawa nama petugas hitung dan nama barang`() {
        val token = adminToken("inv-nm-var")
        val warehouse = location(token, "WH-${uniq()}", "WAREHOUSE")
        val kabel = item(token, "KBL-${uniq()}", "Adapter SC Duplex")
        val custodianId = newUser(token, "Rina Penghitung", "Teknisi")
        receive(token, warehouse, kabel, 25)

        // Observasi SENGAJA beda dari saldo supaya opname-nya berstatus OPEN dan muncul di laporan.
        post(
            "/api/inventory/counts", token,
            """{"locationId":"$warehouse","itemId":"$kabel","observedQuantity":20,"custodianId":"$custodianId",
                "reason":"opname bulanan","evidenceReference":"foto-rak-1",
                "operationKey":"cnt-${uniq()}","payloadHash":"cnt-hash"}""",
        )

        val report = getJson("/api/inventory/variance-report", token)
        val open: List<Map<String, Any?>> = JsonPath.read(report, "$.openCounts")
        val mine = open.single { it["itemId"] == kabel }
        assertThat(mine["custodianName"]).isEqualTo("Rina Penghitung")
        assertThat(mine["itemName"]).isEqualTo("Adapter SC Duplex")
        assertThat(mine["locationKind"]).isEqualTo("WAREHOUSE")
    }

    /**
     * Barang yang sudah DIPENSIUNKAN tetap bernama.
     *
     * Justru baris inilah yang paling butuh dibaca manusia: ledger dan saldo lama menunjuk item
     * yang sudah dinonaktifkan, dan kalau resolusi nama ikut menyaring yang nonaktif, seluruh
     * riwayat barang itu berubah jadi "(tidak dikenal)" tepat ketika seseorang mencoba menelusuri
     * ke mana sisanya pergi.
     */
    @Test
    fun `item yang dinonaktifkan tetap membawa namanya di saldo dan ledger`() {
        val token = adminToken("inv-nm-off")
        val warehouse = location(token, "WH-${uniq()}", "WAREHOUSE")
        val kabel = item(token, "KBL-${uniq()}", "ONT Lama EOL")
        receive(token, warehouse, kabel, 6)

        post("/api/inventory/item-master/$kabel/active", token, """{"active":false}""", expected = 200)

        val row = rows("/api/inventory/balances?itemId=$kabel", token).single()
        assertThat(row["itemName"]).isEqualTo("ONT Lama EOL")
        assertThat(row["itemName"]).isNotEqualTo("(tidak dikenal)")

        val page = getJson("/api/inventory/ledger?page=0&size=20", token)
        assertThat(JsonPath.read<String>(page, "$.content[0].legs[0].itemName")).isEqualTo("ONT Lama EOL")
    }

    /**
     * Nama pengguna tenant lain TIDAK pernah bocor.
     *
     * Resolusi nama berjalan in-process tanpa cek izin `iam.user.view` — itu memang tujuannya —
     * jadi satu-satunya yang menahan kebocoran adalah scope tenant [com.duluin.ftth.iam.IamApi]
     * (RLS). Tes ini menanam id pengguna tenant B sebagai pemegang custody di tenant A: yang boleh
     * muncul hanyalah UUID-nya, bukan namanya.
     */
    @Test
    fun `id pengguna tenant lain tidak pernah teresolusi jadi nama`() {
        val other = adminToken("inv-nm-oth")
        val foreignUser = newUser(other, "Rahasia Tetangga", "Teknisi")

        val token = adminToken("inv-nm-own")
        val warehouse = location(token, "WH-${uniq()}", "WAREHOUSE")
        val van = location(token, "VAN-${uniq()}", "VEHICLE")
        val kabel = item(token, "KBL-${uniq()}", "Konektor Fast SC")
        receive(token, warehouse, kabel, 8)
        post(
            "/api/inventory/issues", token,
            """{"fromLocationId":"$warehouse","custodianId":"$warehouse","technicianId":"$foreignUser",
                "technicianLocationId":"$van","reason":"id tenant sebelah",
                "operationKey":"iss-${uniq()}","payloadHash":"iss-hash",
                "lines":[{"itemId":"$kabel","quantity":3}]}""",
        )

        val issued = rows("/api/inventory/balances?itemId=$kabel", token)
            .single { it["custodyOwnerKind"] == "TECHNICIAN" }
        assertThat(issued["custodyOwnerName"]).isEqualTo(foreignUser)
        assertThat(issued["custodyOwnerName"] as String).doesNotContain("Rahasia")

        val vans = rows("/api/inventory/van-stock?technicianId=$foreignUser", token)
        assertThat(vans.single()["technicianName"]).isEqualTo(foreignUser)
    }
}

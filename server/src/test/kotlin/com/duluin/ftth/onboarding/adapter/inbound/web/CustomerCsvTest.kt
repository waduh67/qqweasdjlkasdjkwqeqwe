package com.duluin.ftth.onboarding.adapter.inbound.web

import com.duluin.ftth.onboarding.application.port.inbound.CustomerExportLine
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.LocalDate

/**
 * Menguji penulis CSV pelanggan: header sesuai template impor, urutan kolom, kolom `mikrotik_password`
 * & `notes` selalu kosong (rahasia tak diekspor), dan escaping RFC-4180 (koma/kutip/baris-baru).
 * Header & urutan yang tepat inilah yang menjamin keluaran ekspor bisa diimpor ulang.
 */
class CustomerCsvTest {

    private val header =
        "name,phone,address,package_name,connection_type,installation_date," +
            "mikrotik_username,mikrotik_password,email,router_name,framed_ip,id_card_number," +
            "next_billing,latitude,longitude,notes"

    @Test
    fun `baris data mengikuti urutan template dengan password dan notes kosong`() {
        val csv = CustomerCsv.render(
            listOf(
                CustomerExportLine(
                    name = "Joko",
                    phone = "0812",
                    address = "Jl. A",
                    packageName = "Home 20",
                    connectionType = "pppoe",
                    installationDate = LocalDate.of(2024, 3, 10),
                    mikrotikUsername = "joko",
                    email = "j@x.test",
                    routerName = "BRAS-01",
                    idCardNumber = "320",
                    nextBillingDay = 10,
                    latitude = -6.2,
                    longitude = 106.8,
                ),
            ),
        )

        val rows = csv.split("\r\n")
        assertThat(rows[0]).isEqualTo(header)
        // Kolom mikrotik_password, framed_ip (pppoe → tak ada), & notes kosong.
        assertThat(rows[1]).isEqualTo("Joko,0812,Jl. A,Home 20,pppoe,2024-03-10,joko,,j@x.test,BRAS-01,,320,10,-6.2,106.8,")
    }

    @Test
    fun `akun static membawa framed_ip di kolomnya`() {
        val csv = CustomerCsv.render(
            listOf(
                CustomerExportLine(
                    name = "Static",
                    phone = null,
                    address = "Jl. Tetap",
                    packageName = "Home 20",
                    connectionType = "static",
                    installationDate = null,
                    mikrotikUsername = "AA:BB:CC:DD:EE:FF",
                    email = null,
                    routerName = "BRAS-01",
                    idCardNumber = null,
                    nextBillingDay = null,
                    latitude = null,
                    longitude = null,
                    framedIp = "100.64.0.10",
                ),
            ),
        )

        // Framed-IP muncul persis setelah router_name (kolom ke-11), MAC jadi mikrotik_username.
        assertThat(csv.split("\r\n")[1])
            .isEqualTo("Static,,Jl. Tetap,Home 20,static,,AA:BB:CC:DD:EE:FF,,,BRAS-01,100.64.0.10,,,,,")
    }

    @Test
    fun `field dengan koma kutip dan baris-baru di-escape`() {
        val csv = CustomerCsv.render(
            listOf(
                CustomerExportLine(
                    name = "Jo\"ko", // kutip → digandakan
                    phone = null,
                    address = "Jl. Melati, No. 3", // koma → dibungkus kutip
                    packageName = null,
                    connectionType = "pppoe",
                    installationDate = null,
                    mikrotikUsername = "joko",
                    email = "baris\npecah", // newline → dibungkus kutip
                    routerName = null,
                    idCardNumber = null,
                    nextBillingDay = null,
                    latitude = null,
                    longitude = null,
                ),
            ),
        )

        val dataRow = csv.split("\r\n")[1]
        assertThat(dataRow).startsWith("\"Jo\"\"ko\",,\"Jl. Melati, No. 3\",")
        assertThat(dataRow).contains(",\"baris\npecah\",")
    }

    @Test
    fun `angka negatif tetap numerik sementara formula tetap diprefix apostrof`() {
        val csv = CustomerCsv.render(
            listOf(
                CustomerExportLine(
                    name = "=2+2",
                    phone = "+62812",
                    address = "@formula",
                    packageName = "-cmd",
                    connectionType = "pppoe",
                    installationDate = null,
                    mikrotikUsername = " \t=1+1",
                    email = "-1+2",
                    routerName = null,
                    idCardNumber = null,
                    nextBillingDay = -6,
                    latitude = -6.0,
                    longitude = -6.2,
                ),
            ),
        )

        assertThat(csv.split("\r\n")[1].split(",")).containsExactly(
            "'=2+2", "'+62812", "'@formula", "'-cmd", "pppoe", "", "' \t=1+1", "",
            "'-1+2", "", "", "", "-6", "-6.0", "-6.2", "",
        )
    }

    @Test
    fun `tanpa baris hanya menghasilkan header`() {
        val csv = CustomerCsv.render(emptyList())
        assertThat(csv).isEqualTo(header + "\r\n")
    }
}

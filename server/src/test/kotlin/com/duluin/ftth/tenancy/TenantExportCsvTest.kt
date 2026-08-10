package com.duluin.ftth.tenancy

import com.duluin.ftth.tenancy.adapter.outbound.persistence.ExportColumn
import com.duluin.ftth.tenancy.adapter.outbound.persistence.csvEscape
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Aturan penulisan sel arsip ekspor tenant.
 *
 * Dua kegagalan di sini sama-sama senyap dan sama-sama mahal: sel yang tak di-escape membuat
 * satu alamat berkoma menggeser seluruh kolom sisanya (arsipnya terbaca, isinya salah), dan
 * kolom rahasia yang lolos penyuntingan mengirim kata sandi ke berkas yang justru sengaja
 * dibagikan ke luar. Keduanya dipatok di sini karena tak satu pun akan memicu galat.
 */
class TenantExportCsvTest {

    @Test
    fun `nilai berkoma, berkutip, atau berbaris baru dibungkus sesuai RFC 4180`() {
        assertThat(csvEscape("Jl. Merdeka No. 1, Bandung")).isEqualTo("\"Jl. Merdeka No. 1, Bandung\"")
        assertThat(csvEscape("ONU \"cadangan\"")).isEqualTo("\"ONU \"\"cadangan\"\"\"")
        assertThat(csvEscape("baris satu\nbaris dua")).isEqualTo("\"baris satu\nbaris dua\"")
    }

    @Test
    fun `nilai polos tak dibungkus supaya berkas tetap enak dibaca`() {
        assertThat(csvEscape("CUST-000123")).isEqualTo("CUST-000123")
        assertThat(csvEscape("")).isEmpty()
    }

    @Test
    fun `kolom rahasia dikenali dari namanya, bukan dari daftar kolom yang harus diingat`() {
        listOf("password_hash", "secret", "coa_secret", "refresh_token", "api_key", "totp_secret")
            .forEach { assertThat(ExportColumn(it, "varchar").secret).`as`(it).isTrue() }
    }

    @Test
    fun `kolom biasa tak ikut tersunting`() {
        listOf("name", "address", "username", "created_at", "status")
            .forEach { assertThat(ExportColumn(it, "varchar").secret).`as`(it).isFalse() }
    }

    @Test
    fun `isi kolom rahasia diganti penanda, tapi NULL tetap sel kosong`() {
        val secret = ExportColumn("password_hash", "varchar")
        assertThat(secret.render("\$2a\$10\$abcdef")).isEqualTo("[disunting]")
        assertThat(secret.render(null)).isEmpty()
    }

    @Test
    fun `kolom geometry dibaca sebagai EWKT, kolom lain apa adanya`() {
        assertThat(ExportColumn("location", "geometry").selectExpression())
            .isEqualTo("""ST_AsEWKT("location") AS "location"""")
        assertThat(ExportColumn("name", "varchar").selectExpression()).isEqualTo("\"name\"")
    }
}

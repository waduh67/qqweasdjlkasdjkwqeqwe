package com.duluin.ftth.iam

import com.duluin.ftth.iam.domain.catalog.PermissionCatalog
import com.duluin.ftth.iam.domain.model.vo.PermissionCode
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Penjaga cepat katalog izin — TANPA Spring/DB, jadi ikut gerbang unit-test biasa.
 *
 * `PermissionCatalog.ALL` di-init eager (`buildList` di class-init) dan tiap baris memanggil
 * `PermissionCode.of`, yang melempar bila kode tak berbentuk `module.resource.action`. Kalau
 * dibiarkan hanya diuji lewat IT `@SpringBootTest`, satu kode salah ketik bikin SELURUH konteks
 * gagal load — dan itu cuma ketahuan di CI yang punya Postgres. Test ini memaksa init + memindai
 * setiap kode, jadi typo semacam `report.view` (2 segmen) gagal di sini, bukan jauh di hilir.
 */
class PermissionCatalogTest {

    @Test
    fun `semua kode izin berbentuk module_resource_action`() {
        // Menyentuh ALL memaksa class-init; bila ada kode invalid, ExceptionInInitializerError di sini.
        assertThat(PermissionCatalog.ALL).isNotEmpty()

        val pattern = Regex("^[a-z]+\\.[a-z]+\\.[a-z]+$")
        val menyimpang = PermissionCatalog.ALL.map { it.code.value }.filterNot { pattern.matches(it) }
        assertThat(menyimpang)
            .withFailMessage("Kode izin harus module.resource.action, menyimpang: %s", menyimpang)
            .isEmpty()
    }

    @Test
    fun `tak ada kode izin duplikat`() {
        val semua = PermissionCatalog.ALL.map { it.code.value }
        assertThat(semua).doesNotHaveDuplicates()
        // codes (Set) harus mencakup persis semua baris — cermin cepat yang dipakai validasi lain.
        assertThat(PermissionCatalog.codes).hasSameSizeAs(semua)
    }

    @Test
    fun `modul kode selaras dengan segmen pertama`() {
        // Memastikan aksesor VO tak bergeser dari string mentah — mis. reporting.report.view -> reporting.
        val laporan = PermissionCode.of("reporting.report.view")
        assertThat(laporan.module).isEqualTo("reporting")
        assertThat(laporan.resource).isEqualTo("report")
        assertThat(laporan.action).isEqualTo("view")
    }
}

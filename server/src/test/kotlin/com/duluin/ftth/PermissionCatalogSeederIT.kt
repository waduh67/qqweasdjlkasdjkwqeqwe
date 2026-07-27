package com.duluin.ftth

import com.duluin.ftth.iam.adapter.outbound.persistence.PermissionJpaRepository
import com.duluin.ftth.iam.application.service.PermissionCatalogSeeder
import com.duluin.ftth.iam.domain.catalog.PermissionCatalog
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles

/**
 * Seeder katalog izin harus MEREKONSILIASI `platformOnly`, bukan cuma menyisipkan baris baru.
 * Regresi nyata (pivot VPN-as-a-service): kode `vpn.server.*` semula tenant-assignable, lalu
 * dipindah jadi platform-only. DB yang sudah pernah jalan menyimpan `platform_only=false`;
 * tanpa rekonsiliasi, "Tenant Admin" (yang mengambil semua izin non-platform) ikut kebagian
 * izin mengelola hub — bocor lintas-batas platform. Test suite lain tak menangkap ini karena
 * tiap run memakai DB fresh (baris dibuat dari nol dengan flag yang sudah benar); di sini kita
 * simulasikan DB lama dengan menurunkan flag dulu, lalu tegakkan sync mengembalikannya.
 */
@SpringBootTest
@ActiveProfiles("test")
class PermissionCatalogSeederIT {

    @Autowired private lateinit var seeder: PermissionCatalogSeeder
    @Autowired private lateinit var jpa: PermissionJpaRepository

    @Test
    fun `sync mengoreksi platformOnly untuk kode yang berpindah klasifikasi`() {
        // Ambil satu kode yang katalog tandai platform-only (mis. vpn.server.view).
        val platformCode = PermissionCatalog.ALL.first { it.platformOnly }.code.value
        val entity = jpa.findAll().first { it.code == platformCode }

        // Simulasikan DB lama: seolah kode ini dulu tenant-assignable.
        entity.platformOnly = false
        jpa.saveAndFlush(entity)
        assertThat(jpa.findById(entity.id).get().platformOnly).isFalse()

        // Sync harus mengembalikan flag ke platform-only.
        seeder.sync()

        assertThat(jpa.findById(entity.id).get().platformOnly).isTrue()
    }
}

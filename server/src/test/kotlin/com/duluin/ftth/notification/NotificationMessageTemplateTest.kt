package com.duluin.ftth.notification

import com.duluin.ftth.common.domain.UuidV7
import com.duluin.ftth.common.domain.error.ValidationException
import com.duluin.ftth.notification.domain.model.NotificationMessageTemplate
import com.duluin.ftth.notification.domain.model.NotificationSettings
import com.duluin.ftth.notification.domain.model.TemplateCategory
import com.duluin.ftth.notification.domain.model.TemplateSource
import com.duluin.ftth.notification.domain.model.TemplateStatus
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * Aturan penamaan template Meta ditegakkan di domain supaya operator tahu salahnya
 * sebelum pesan ditolak Meta saat kirim — bukan sesudahnya.
 */
class NotificationMessageTemplateTest {

    private val tenantId: UUID = UuidV7.generate()

    @Test
    fun `nama dinormalkan ke huruf kecil dan bahasa kosong jatuh ke bawaan`() {
        val template = NotificationMessageTemplate.create(tenantId, "  Tagihan_Jatuh_Tempo ", "  ")

        assertThat(template.name).isEqualTo("tagihan_jatuh_tempo")
        assertThat(template.language).isEqualTo(NotificationSettings.DEFAULT_TEMPLATE_LANG)
        assertThat(template.category).isEqualTo(TemplateCategory.UTILITY)
        assertThat(template.status).isEqualTo(TemplateStatus.UNKNOWN)
        assertThat(template.source).isEqualTo(TemplateSource.MANUAL)
    }

    @Test
    fun `nama di luar aturan Meta ditolak`() {
        assertThatThrownBy { NotificationMessageTemplate.create(tenantId, "tagihan-jatuh tempo", "id") }
            .isInstanceOf(ValidationException::class.java)
        assertThatThrownBy { NotificationMessageTemplate.create(tenantId, "  ", "id") }
            .isInstanceOf(ValidationException::class.java)
        assertThatThrownBy { NotificationMessageTemplate.create(tenantId, "a".repeat(129), "id") }
            .isInstanceOf(ValidationException::class.java)
    }

    @Test
    fun `kode bahasa hanya menerima bentuk Meta`() {
        assertThat(NotificationMessageTemplate.create(tenantId, "halo", "en_US").language).isEqualTo("en_US")
        assertThatThrownBy { NotificationMessageTemplate.create(tenantId, "halo", "indonesia") }
            .isInstanceOf(ValidationException::class.java)
    }

    @Test
    fun `menghitung placeholder unik bukan kemunculan`() {
        assertThat(NotificationMessageTemplate.countBodyParams(null)).isZero()
        assertThat(NotificationMessageTemplate.countBodyParams("Tanpa parameter.")).isZero()
        assertThat(NotificationMessageTemplate.countBodyParams("Halo {{1}}, tagihan {{1}} jatuh tempo.")).isEqualTo(1)
        assertThat(NotificationMessageTemplate.countBodyParams("Halo {{1}}, nominal {{ 2 }}.")).isEqualTo(2)
    }

    @Test
    fun `sync dari Meta menimpa status kategori dan pratinjau`() {
        val template = NotificationMessageTemplate.create(tenantId, "tagihan", "id")
        val at = Instant.parse("2026-08-08T00:00:00Z")

        template.applyRemote("991", TemplateCategory.UTILITY, TemplateStatus.APPROVED, "Halo {{1}}", at)

        assertThat(template.metaTemplateId).isEqualTo("991")
        assertThat(template.status).isEqualTo(TemplateStatus.APPROVED)
        assertThat(template.source).isEqualTo(TemplateSource.META)
        assertThat(template.bodyPreview).isEqualTo("Halo {{1}}")
        assertThat(template.bodyParamCount).isEqualTo(1)
        assertThat(template.syncedAt).isEqualTo(at)
    }

    @Test
    fun `ganti nama membuang hasil sync lama karena menunjuk template Meta lain`() {
        val template = NotificationMessageTemplate.create(tenantId, "tagihan", "id")
        template.applyRemote("991", TemplateCategory.UTILITY, TemplateStatus.APPROVED, "Halo {{1}}", Instant.EPOCH)

        template.rename("tagihan_menunggak", "id")

        assertThat(template.name).isEqualTo("tagihan_menunggak")
        assertThat(template.status).isEqualTo(TemplateStatus.UNKNOWN)
        assertThat(template.source).isEqualTo(TemplateSource.MANUAL)
        assertThat(template.metaTemplateId).isNull()
        assertThat(template.bodyPreview).isNull()
        assertThat(template.syncedAt).isNull()
    }

    @Test
    fun `ganti nama ke nilai yang sama mempertahankan hasil sync`() {
        val template = NotificationMessageTemplate.create(tenantId, "tagihan", "id")
        template.applyRemote("991", TemplateCategory.UTILITY, TemplateStatus.APPROVED, "Halo {{1}}", Instant.EPOCH)

        // Menyimpan ulang tanpa mengubah apa pun tak boleh menghapus status persetujuan.
        template.rename("TAGIHAN", null)

        assertThat(template.status).isEqualTo(TemplateStatus.APPROVED)
        assertThat(template.metaTemplateId).isEqualTo("991")
    }
}

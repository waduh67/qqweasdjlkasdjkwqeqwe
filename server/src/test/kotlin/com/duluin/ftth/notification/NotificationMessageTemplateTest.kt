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
 * Aturan penamaan & isi template ditegakkan di domain supaya operator tahu salahnya SEBELUM
 * pengajuan dikirim ke penyedia — bukan setelah ditolak peninjau berhari-hari kemudian.
 */
class NotificationMessageTemplateTest {

    private val tenantId: UUID = UuidV7.generate()

    private fun draft(name: String?, language: String? = "id", body: String = BODY) =
        NotificationMessageTemplate.draft(tenantId, name, language, TemplateCategory.UTILITY, body)

    @Test
    fun `draft menormalkan nama dan jatuh ke bahasa bawaan`() {
        val template = draft("  Tagihan_Jatuh_Tempo ", "  ")

        assertThat(template.name).isEqualTo("tagihan_jatuh_tempo")
        assertThat(template.language).isEqualTo(NotificationSettings.DEFAULT_TEMPLATE_LANG)
        assertThat(template.category).isEqualTo(TemplateCategory.UTILITY)
        // Draft langsung PENDING & REMOTE: itulah nasibnya begitu penyedia menerima pengajuan.
        assertThat(template.status).isEqualTo(TemplateStatus.PENDING)
        assertThat(template.source).isEqualTo(TemplateSource.REMOTE)
        assertThat(template.remoteId).isNull()
        assertThat(template.bodyParamCount).isEqualTo(1)
    }

    @Test
    fun `nama di luar aturan Meta ditolak`() {
        assertThatThrownBy { draft("tagihan-jatuh tempo") }.isInstanceOf(ValidationException::class.java)
        assertThatThrownBy { draft("  ") }.isInstanceOf(ValidationException::class.java)
        assertThatThrownBy { draft("a".repeat(129)) }.isInstanceOf(ValidationException::class.java)
    }

    @Test
    fun `kode bahasa hanya menerima bentuk Meta`() {
        assertThat(draft("halo", "en_US").language).isEqualTo("en_US")
        assertThatThrownBy { draft("halo", "indonesia") }.isInstanceOf(ValidationException::class.java)
    }

    @Test
    fun `isi pesan wajib memuat tepat satu jenis variabel satu`() {
        // Berulang kali {{1}} tetap sah — yang dihitung jenisnya, bukan kemunculannya.
        assertThat(draft("halo", body = "Halo {{1}}, ulangi {{1}}").bodyParamCount).isEqualTo(1)

        assertThatThrownBy { draft("halo", body = "Tanpa variabel sama sekali") }
            .isInstanceOf(ValidationException::class.java)
        assertThatThrownBy { draft("halo", body = "Halo {{1}}, nominal {{2}}") }
            .isInstanceOf(ValidationException::class.java)
        assertThatThrownBy { draft("halo", body = "Halo {{2}}") }
            .isInstanceOf(ValidationException::class.java)
    }

    @Test
    fun `isi pesan menolak baris kosong tab dan spasi beruntun`() {
        assertThatThrownBy { draft("halo", body = "Halo {{1}}\n\nsalam") }
            .isInstanceOf(ValidationException::class.java)
        assertThatThrownBy { draft("halo", body = "Halo\t{{1}}") }
            .isInstanceOf(ValidationException::class.java)
        assertThatThrownBy { draft("halo", body = "Halo      {{1}}") }
            .isInstanceOf(ValidationException::class.java)
        assertThatThrownBy { draft("halo", body = "{{1}}" + "a".repeat(NotificationMessageTemplate.MAX_BODY)) }
            .isInstanceOf(ValidationException::class.java)
    }

    @Test
    fun `sync dari penyedia menimpa id status kategori dan isi`() {
        val template = NotificationMessageTemplate.mirror(tenantId, "tagihan", "id")
        val at = Instant.parse("2026-08-08T00:00:00Z")

        template.applyRemote("991", TemplateCategory.MARKETING, TemplateStatus.APPROVED, "Halo {{1}}", at)

        assertThat(template.remoteId).isEqualTo("991")
        assertThat(template.category).isEqualTo(TemplateCategory.MARKETING)
        assertThat(template.status).isEqualTo(TemplateStatus.APPROVED)
        assertThat(template.source).isEqualTo(TemplateSource.REMOTE)
        assertThat(template.bodyText).isEqualTo("Halo {{1}}")
        assertThat(template.bodyParamCount).isEqualTo(1)
        assertThat(template.syncedAt).isEqualTo(at)
    }

    @Test
    fun `editBody hanya menyentuh isi dan kategori`() {
        val template = draft("tagihan")
        template.applyRemote("991", TemplateCategory.UTILITY, TemplateStatus.APPROVED, BODY, Instant.EPOCH)

        template.editBody("Versi baru {{1}}", TemplateCategory.MARKETING)

        assertThat(template.bodyText).isEqualTo("Versi baru {{1}}")
        assertThat(template.category).isEqualTo(TemplateCategory.MARKETING)
        // Nama, bahasa, dan id penyedia terkunci — menyunting bukan berarti menunjuk template lain.
        assertThat(template.name).isEqualTo("tagihan")
        assertThat(template.remoteId).isEqualTo("991")
        // Status TIDAK ditebak lokal; jawaban penyedia yang menentukannya lewat applyRemote.
        assertThat(template.status).isEqualTo(TemplateStatus.APPROVED)
    }

    @Test
    fun `baris yang hilang di penyedia dinonaktifkan bukan dihapus`() {
        val template = draft("tagihan")
        template.applyRemote("991", TemplateCategory.UTILITY, TemplateStatus.APPROVED, BODY, Instant.EPOCH)
        val at = Instant.parse("2026-08-08T00:00:00Z")

        template.markMissingRemotely(at)

        assertThat(template.status).isEqualTo(TemplateStatus.DISABLED)
        assertThat(template.syncedAt).isEqualTo(at)
        // remoteId dipertahankan: pemetaan pemicunya masih menunjuk baris ini.
        assertThat(template.remoteId).isEqualTo("991")
    }

    @Test
    fun `menghitung placeholder unik bukan kemunculan`() {
        assertThat(NotificationMessageTemplate.countBodyParams(null)).isZero()
        assertThat(NotificationMessageTemplate.countBodyParams("Tanpa parameter.")).isZero()
        assertThat(NotificationMessageTemplate.countBodyParams("Halo {{1}}, tagihan {{1}} jatuh tempo.")).isEqualTo(1)
        assertThat(NotificationMessageTemplate.countBodyParams("Halo {{1}}, nominal {{ 2 }}.")).isEqualTo(2)
    }

    private companion object {
        const val BODY = "Halo, {{1}}"
    }
}

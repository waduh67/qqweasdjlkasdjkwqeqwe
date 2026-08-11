package com.duluin.ftth.notification

import com.duluin.ftth.notification.application.service.EmailRenderer
import com.duluin.ftth.notification.application.service.ResolvedEmailIdentity
import com.duluin.ftth.notification.domain.model.EmailBranding
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Bentuk surat yang berangkat. Tiga hal yang dijaga di sini karena kegagalannya mahal:
 *
 *  1. **Isi pesan tak pernah dipercaya sebagai markup.** Nama pelanggan dan catatan operator
 *     masuk ke badan email apa adanya; satu `<` yang lolos escape berarti tampilan surat
 *     ditentukan oleh orang yang mengetik namanya.
 *  2. **Bagian teks polos tetap seperti dulu.** Pemulihan password harus terbaca di klien
 *     paling sederhana, dan perilaku sebelum fitur ini ada tak boleh ikut berubah.
 *  3. **Logo cuma hiasan.** Banyak klien memblokir gambar remote secara bawaan, jadi surat
 *     tanpa logo wajib tetap utuh — termasuk tetap menyebut siapa pengirimnya.
 */
class EmailRendererTest {

    private val renderer = EmailRenderer()

    private fun identity(
        fromName: String = "PT Sinar Jaya Net",
        logoUrl: String? = null,
        accentColor: String? = null,
        footerText: String? = null,
        signatureText: String? = null,
    ) = ResolvedEmailIdentity(
        fromAddress = "billing@sinarjaya.id",
        fromName = fromName,
        replyTo = null,
        branding = EmailBranding(null, null, accentColor, footerText, signatureText),
        logoUrl = logoUrl,
    )

    @Test
    fun `isi pesan di-escape sebelum masuk HTML`() {
        val body = "Halo <script>alert('x')</script> & selamat datang"

        val html = renderer.renderHtml("Selamat datang", body, identity())

        assertThat(html).doesNotContain("<script>")
        assertThat(html).contains("&lt;script&gt;").contains("&amp; selamat datang")
    }

    @Test
    fun `subjek pun di-escape karena ikut ditulis ke judul dokumen`() {
        val html = renderer.renderHtml("Tagihan <b>jatuh tempo</b>", "isi", identity())

        assertThat(html).contains("<title>Tagihan &lt;b&gt;jatuh tempo&lt;/b&gt;</title>")
    }

    @Test
    fun `tanpa logo, kop diisi nama pengirim alih-alih kosong`() {
        val html = renderer.renderHtml("Subjek", "isi", identity(logoUrl = null))

        assertThat(html).doesNotContain("<img")
        assertThat(html).contains("PT Sinar Jaya Net")
    }

    @Test
    fun `logo dirender hanya bila URL-nya ada`() {
        val html = renderer.renderHtml(
            "Subjek",
            "isi",
            identity(logoUrl = "https://app.duluin.net/api/public/email-logo"),
        )

        assertThat(html)
            .contains("""<img src="https://app.duluin.net/api/public/email-logo"""")
            // Alt text = nama pengirim, supaya klien yang memblokir gambar tetap tahu siapa ini.
            .contains("""alt="PT Sinar Jaya Net"""")
    }

    @Test
    fun `tanda tangan dan footer muncul di kedua bagian surat`() {
        val id = identity(footerText = "Email otomatis, mohon tak dibalas", signatureText = "Salam, Tim Dukungan")

        val text = renderer.renderText("Tagihan Anda jatuh tempo besok.", id)
        val html = renderer.renderHtml("Subjek", "Tagihan Anda jatuh tempo besok.", id)

        assertThat(text).contains("Salam, Tim Dukungan").contains("Email otomatis, mohon tak dibalas")
        assertThat(html).contains("Salam, Tim Dukungan").contains("Email otomatis, mohon tak dibalas")
    }

    @Test
    fun `tanpa footer dan tanda tangan, bagian teks polos sama persis dengan pesan aslinya`() {
        val body = "Kode pemulihan Anda: 483920\nBerlaku 15 menit."

        assertThat(renderer.renderText(body, identity())).isEqualTo(body)
    }

    @Test
    fun `warna aksen tenant dipakai, bawaannya biru tenang`() {
        assertThat(renderer.renderHtml("S", "isi", identity(accentColor = "#ff8800"))).contains("#ff8800")
        assertThat(renderer.renderHtml("S", "isi", identity())).contains("#2563eb")
    }

    @Test
    fun `baris kosong jadi paragraf dan pindah baris tunggal jadi br`() {
        val html = renderer.renderHtml("S", "Baris satu\nBaris dua\n\nParagraf kedua", identity())

        assertThat(html).contains("Baris satu<br />Baris dua")
        assertThat(html).contains("<p style=\"margin:0 0 12px;\">Paragraf kedua</p>")
    }

    @Test
    fun `URL polos di badan pesan ditautkan`() {
        val html = renderer.renderHtml("S", "Bayar di https://app.duluin.net/portal/inv-1 ya.", identity())

        assertThat(html).contains("""<a href="https://app.duluin.net/portal/inv-1"""")
        // Bagian teks polos tak boleh ikut ber-markup — di sana URL memang sudah bisa diklik.
        assertThat(renderer.renderText("Bayar di https://app.duluin.net/portal/inv-1 ya.", identity()))
            .doesNotContain("<a href")
    }

    @Test
    fun `render merakit surat lengkap dari identitas teresolusi`() {
        val id = identity(fromName = "Sinar Jaya Support").copy(replyTo = "cs@sinarjaya.id")

        val message = renderer.render("budi@contoh.id", "Tagihan Anda", "isi pesan", id)

        assertThat(message.to).isEqualTo("budi@contoh.id")
        assertThat(message.subject).isEqualTo("Tagihan Anda")
        assertThat(message.fromName).isEqualTo("Sinar Jaya Support")
        assertThat(message.fromAddress).isEqualTo("billing@sinarjaya.id")
        assertThat(message.replyTo).isEqualTo("cs@sinarjaya.id")
        assertThat(message.textBody).isEqualTo("isi pesan")
        assertThat(message.htmlBody).contains("isi pesan").contains("<!DOCTYPE html>")
    }
}

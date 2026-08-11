package com.duluin.ftth.notification.application.service

import com.duluin.ftth.notification.application.port.outbound.OutboundEmail
import org.springframework.stereotype.Component

/**
 * Merakit badan email dari (subjek, teks pesan, identitas teresolusi) jadi dua bentuk:
 * HTML berbingkai merek dan teks polos.
 *
 * Tiga hal yang membentuk keputusan di sini:
 *
 *  1. **Isi pesan tak disentuh.** Kalimatnya sudah dirangkai listener yang menerbitkan
 *     peristiwanya; yang ditambahkan renderer hanyalah bungkus (logo, garis aksen, tanda
 *     tangan, footer). Bagian teks polosnya karena itu praktis sama dengan pesan lama —
 *     perilaku sebelum fitur ini ada tetap utuh bagi klien teks.
 *  2. **HTML-nya kuno dengan sengaja.** Tabel 600px dan `style=` inline, bukan flexbox atau
 *     stylesheet: klien email (Outlook desktop terutama) merender dengan mesin yang
 *     tertinggal dua dekade, dan CSS eksternal umumnya dibuang mentah-mentah.
 *  3. **Logo cuma hiasan.** Banyak klien memblokir gambar remote secara bawaan, jadi layout
 *     wajib tetap terbaca utuh saat `logoUrl` null atau gambarnya tak dimuat.
 */
@Component
class EmailRenderer {

    /** Rakit satu email siap kirim untuk [to]. */
    fun render(to: String, subject: String, body: String, identity: ResolvedEmailIdentity): OutboundEmail =
        OutboundEmail(
            to = to,
            subject = subject,
            textBody = renderText(body, identity),
            htmlBody = renderHtml(subject, body, identity),
            fromName = identity.fromName,
            fromAddress = identity.fromAddress,
            replyTo = identity.replyTo,
        )

    /** Bagian teks polos: pesan asli, lalu tanda tangan & footer bila ada. */
    fun renderText(body: String, identity: ResolvedEmailIdentity): String = buildString {
        append(body.trimEnd())
        identity.branding.signatureText?.let { append("\n\n").append(it) }
        identity.branding.footerText?.let { append("\n\n--\n").append(it) }
    }

    /**
     * Bagian HTML. Dipisah sebagai fungsi publik karena layar setelan memakainya untuk
     * pratinjau — pratinjau yang dirender jalur lain cepat atau lambat akan berbohong.
     */
    fun renderHtml(subject: String, body: String, identity: ResolvedEmailIdentity): String {
        val accent = identity.branding.accentColor ?: DEFAULT_ACCENT
        val header = identity.logoUrl?.let { url ->
            """<img src="${escape(url)}" alt="${escape(identity.fromName)}" height="40"
                 style="display:block;border:0;max-height:40px;" />"""
        } ?: """<div style="font-size:18px;font-weight:600;color:#1f2933;">${escape(identity.fromName)}</div>"""

        val signature = identity.branding.signatureText
            ?.let { """<p style="margin:24px 0 0;color:#52606d;">${paragraphs(it, accent)}</p>""" }
            .orEmpty()
        val footer = identity.branding.footerText
            ?.let {
                """<td style="padding:16px 24px;background:#f5f7fa;border-top:1px solid #e4e7eb;
                    font-size:12px;line-height:18px;color:#7b8794;">${paragraphs(it, accent)}</td>"""
            }
            ?: """<td style="padding:8px;"></td>"""

        return """
            <!DOCTYPE html>
            <html lang="id">
            <head>
              <meta charset="utf-8" />
              <meta name="viewport" content="width=device-width,initial-scale=1" />
              <title>${escape(subject)}</title>
            </head>
            <body style="margin:0;padding:24px 12px;background:#eef1f5;
                         font-family:-apple-system,'Segoe UI',Roboto,Helvetica,Arial,sans-serif;">
              <table role="presentation" width="100%" cellpadding="0" cellspacing="0" border="0">
                <tr>
                  <td align="center">
                    <table role="presentation" width="600" cellpadding="0" cellspacing="0" border="0"
                           style="width:600px;max-width:100%;background:#ffffff;border-radius:8px;overflow:hidden;">
                      <tr><td style="height:4px;background:$accent;font-size:0;line-height:0;">&nbsp;</td></tr>
                      <tr><td style="padding:24px 24px 0;">$header</td></tr>
                      <tr>
                        <td style="padding:16px 24px 24px;font-size:15px;line-height:22px;color:#1f2933;">
                          ${paragraphs(body, accent)}
                          $signature
                        </td>
                      </tr>
                      <tr>$footer</tr>
                    </table>
                  </td>
                </tr>
              </table>
            </body>
            </html>
        """.trimIndent()
    }

    /**
     * Teks apa adanya → HTML aman: di-escape lebih dulu (isi pesan mengandung nama pelanggan
     * dan nomor tagihan yang tak pernah boleh dipercaya sebagai markup), baris kosong jadi
     * paragraf, sisa `\n` jadi `<br>`, lalu URL ditautkan.
     */
    private fun paragraphs(text: String, accent: String): String =
        text.trim().split(PARAGRAPH_BREAK).joinToString("\n") { block ->
            val html = autolink(escape(block).replace("\n", "<br />"), accent)
            """<p style="margin:0 0 12px;">$html</p>"""
        }

    /**
     * Tautkan URL yang tertulis polos di pesan. Dijalankan SETELAH escape, jadi `&` di query
     * string sudah menjadi `&amp;` — bentuk yang justru benar di dalam atribut `href`.
     */
    private fun autolink(escapedHtml: String, accent: String): String =
        URL_PATTERN.replace(escapedHtml) { match ->
            val url = match.value.trimEnd('.', ',', ')')
            val trailing = match.value.removePrefix(url)
            """<a href="$url" style="color:$accent;">$url</a>$trailing"""
        }

    private fun escape(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    private companion object {
        /** Biru tenang; dipakai bila platform maupun tenant belum memilih warna aksen. */
        const val DEFAULT_ACCENT = "#2563eb"
        val PARAGRAPH_BREAK = Regex("\\n\\s*\\n")
        val URL_PATTERN = Regex("https?://[^\\s<]+")
    }
}

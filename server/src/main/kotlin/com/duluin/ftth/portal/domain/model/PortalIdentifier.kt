package com.duluin.ftth.portal.domain.model

/**
 * Jenis identitas yang boleh dipakai pelanggan untuk masuk portal.
 *
 * Tiga-tiganya setara di layar masuk — pelanggan mengetik satu kotak saja. [PHONE] ada
 * karena di basis pelanggan ISP Indonesia nomor HP jauh lebih universal daripada email:
 * hampir semua punya WhatsApp, tak semua punya email yang benar-benar dibaca.
 */
enum class PortalIdentityKind { LOGIN, EMAIL, PHONE }

/**
 * Normalisasi identitas portal — satu-satunya tempat yang memutuskan bentuk KANONIK
 * sebuah identitas, dipakai baik saat MENULIS indeks (`portal_identity`) maupun saat
 * MENCOCOKKAN apa yang diketik pelanggan. Kalau kedua sisi ini pernah berbeda pendapat,
 * pelanggan yang sah akan ditolak tanpa jejak — jadi keduanya sengaja dijalankan lewat
 * fungsi yang sama di sini.
 *
 * CATATAN: [phone] harus tetap cermin dari normalisasi nomor pada backfill SQL di
 * `V79__portal_identity_and_password_reset.sql`.
 */
object PortalIdentifier {

    /** Batas kolom `portal_identity.value`. Nilai lebih panjang tak bisa disimpan → diabaikan. */
    const val MAX_LENGTH = 255

    /**
     * Email ternormalkan (lower-case), atau null bila jelas bukan email.
     *
     * Validasinya sengaja longgar — cukup ada `@` yang diapit sesuatu. Ini indeks
     * pencarian, bukan gerbang pendaftaran: alamat aneh-tapi-sah tak boleh membuat
     * pelanggan kehilangan jalan masuk, dan alamat ngawur toh tak akan cocok apa pun.
     */
    fun email(raw: String?): String? {
        val value = raw?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
        val at = value.indexOf('@')
        if (at <= 0 || at == value.length - 1) return null
        return value.takeIf { it.length <= MAX_LENGTH }
    }

    /**
     * Nomor HP dalam bentuk kanonik kode-negara (`0812…` dan `+62 812…` sama-sama jadi
     * `62812…`), atau null bila bukan nomor yang masuk akal.
     *
     * Pemisah apa pun (spasi, tanda hubung, kurung) dibuang lebih dulu supaya nomor yang
     * sama tak tersimpan dalam beberapa rupa. Terlalu pendek/panjang = isian sampah yang
     * memang tak bisa dihubungi, jadi tak layak jadi identitas.
     */
    fun phone(raw: String?): String? {
        val digits = raw?.filter(Char::isDigit)?.takeIf { it.isNotEmpty() } ?: return null
        val normalized = if (digits.startsWith("0")) "62" + digits.substring(1) else digits
        return normalized.takeIf { it.length in MIN_PHONE_LENGTH..MAX_PHONE_LENGTH }
    }

    /** Username portal ternormalkan, atau null bila melanggar aturan bentuk login. */
    fun login(raw: String?): String? =
        raw?.let { runCatching { PortalCredential.normalizeLogin(it) }.getOrNull() }

    /**
     * Semua bentuk kanonik yang PANTAS dicoba untuk satu ketikan pelanggan.
     *
     * Sengaja mengembalikan beberapa, bukan menebak satu jenis, karena jenisnya memang
     * tak selalu bisa dipastikan: username PPPoE di lapangan sering berupa deretan angka
     * yang persis seperti nomor HP. Kalau kita memaksa memilih, salah satu golongan
     * pelanggan pasti tak bisa masuk — jadi angka dicoba sebagai nomor HP DAN sebagai
     * username apa adanya.
     */
    fun candidates(raw: String): List<String> {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return emptyList()
        // LinkedHashSet: urutan tetap (enak dibaca di log) dan tanpa duplikat bila
        // beberapa jalur normalisasi kebetulan menghasilkan nilai yang sama.
        return LinkedHashSet<String>().apply {
            email(trimmed)?.let(::add)
            phone(trimmed)?.let(::add)
            login(trimmed)?.let(::add)
        }.toList()
    }

    /** Terlalu pendek untuk nomor Indonesia mana pun; kemungkinan besar salah ketik. */
    private const val MIN_PHONE_LENGTH = 8

    /** Batas atas E.164. */
    private const val MAX_PHONE_LENGTH = 20
}

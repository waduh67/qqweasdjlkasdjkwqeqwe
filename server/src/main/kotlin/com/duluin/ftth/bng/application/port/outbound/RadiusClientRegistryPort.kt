package com.duluin.ftth.bng.application.port.outbound

import java.util.UUID

/**
 * Registri klien RADIUS platform — tabel `nas` di radius-db yang jadi sumber
 * "dynamic clients" FreeRADIUS: daftar BRAS dibaca langsung dari DB, jadi tenant
 * cukup mendaftarkan router (IP + secret) lewat aplikasi; nol sentuh `clients.conf`,
 * nol restart FreeRADIUS. Ini inti self-service RADIUS-as-a-service.
 *
 * Semua operasi IDEMPOTEN (DELETE-lalu-INSERT berdasar [nasname]) dan digerbangi
 * [isConfigured] — di dev/test tanpa radius-db, pemanggil melewati registrasi diam-diam.
 */
interface RadiusClientRegistryPort {

    /** True bila pool radius-db tersedia; bila false, semua operasi lain harus dilewati. */
    fun isConfigured(): Boolean

    /**
     * Daftarkan/perbarui satu BRAS sebagai klien RADIUS.
     *
     * [nasname] = alamat sumber yang dilihat FreeRADIUS dari BRAS (IP publik/overlay),
     * jadi kunci baris; [shortname] = kode tenant (`slug`) yang dipakai FreeRADIUS untuk
     * `sql_user_name = "%{client:shortname}:%{User-Name}"` demi isolasi baris SQL antar-tenant;
     * [secret] = shared secret RADIUS (plaintext — radius-db internal platform, FreeRADIUS
     * butuh apa adanya).
     */
    fun register(tenantId: UUID, nasname: String, shortname: String, secret: String)

    /** Cabut klien RADIUS beralamat [nasname] (idempoten; no-op bila tak ada). */
    fun deregister(tenantId: UUID, nasname: String)
}

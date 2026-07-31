package com.duluin.ftth.bng.application.port.outbound

import com.duluin.ftth.bng.domain.model.Nas

/**
 * Jalur BACA ke RouterOS sebuah BRAS (vendor MIKROTIK) lewat REST API — dipakai bulk-import
 * PPPoE: menarik daftar `/ppp/secret` (akun PPPoE yang sudah ada di router) agar bisa
 * dimigrasikan menjadi pelanggan+langganan+akun di sistem lalu dipindah ke RADIUS pusat.
 *
 * Kredensial & koordinat diambil dari [Nas] (apiUsername/apiSecret/apiPort/apiUseTls/address).
 * Berbeda dari jalur RADIUS (auth/acct/CoA) yang router tembak ke server, ini menyentuh
 * router LANGSUNG — hanya masuk akal untuk MIKROTIK yang punya REST API; adapter menolak
 * vendor lain. Murni baca; TAK menulis apa pun ke router.
 */
interface RouterOsPort {

    /** Tarik seluruh `/ppp/secret` dari RouterOS sebuah BRAS. */
    fun fetchPppSecrets(nas: Nas): List<PppSecret>
}

/**
 * Satu baris `/ppp/secret` RouterOS — bahan mentah bulk-import. [name] = username PPPoE;
 * [password] = plaintext di router (dipakai apa adanya agar pelanggan tetap bisa login
 * setelah pindah ke RADIUS pusat); [profile] = profil RouterOS (dipetakan operator ke paket
 * katalog); [service] biasanya `pppoe`/`any`; [comment] sering memuat nama/ID pelanggan;
 * [disabled] menandai akun dimatikan di router (biasanya dilewati saat impor).
 */
data class PppSecret(
    val name: String,
    val password: String?,
    val profile: String?,
    val service: String?,
    val comment: String?,
    val disabled: Boolean,
)

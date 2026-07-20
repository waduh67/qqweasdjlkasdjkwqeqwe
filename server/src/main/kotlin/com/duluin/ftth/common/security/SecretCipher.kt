package com.duluin.ftth.common.security

/**
 * Enkripsi simetris untuk rahasia yang harus bisa dibaca kembali oleh aplikasi —
 * community string SNMP, kredensial Telnet OLT, API key collector. Berbeda dari
 * password pengguna yang di-hash satu arah dan tidak pernah didekripsi.
 *
 * Dideklarasikan sebagai port di shared kernel agar module bisnis tidak bergantung
 * pada detail algoritma.
 */
interface SecretCipher {

    /** Menghasilkan ciphertext yang aman disimpan di kolom database. */
    fun encrypt(plaintext: String): String

    fun decrypt(ciphertext: String): String
}

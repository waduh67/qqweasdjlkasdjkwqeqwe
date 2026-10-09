package com.duluin.ftth.mobile.storage

import android.content.Context
import com.duluin.ftth.mobile.domain.TechnicianCredentialStore
import java.util.Base64

class AndroidTechnicianCredentials(context: Context) : TechnicianCredentialStore {
    private val preferences = context.getSharedPreferences("ftth.secure.session", Context.MODE_PRIVATE)
    private val cipher = AndroidKeystoreCipher()
    override fun read(): String? = preferences.getString("session", null)?.let {
        cipher.decrypt(EncryptedBlob("v1", Base64.getDecoder().decode(it))).decodeToString()
    }
    override fun write(value: String) {
        val encrypted = Base64.getEncoder().encodeToString(cipher.encrypt(value.encodeToByteArray()).bytes)
        check(preferences.edit().putString("session", encrypted).commit()) { "Penyimpanan sesi gagal." }
    }
    override fun clear() { check(preferences.edit().clear().commit()) { "Penghapusan sesi gagal." } }
}

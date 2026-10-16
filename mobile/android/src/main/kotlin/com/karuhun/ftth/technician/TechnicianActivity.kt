package com.karuhun.ftth.technician

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.karuhun.ftth.mobile.app.ReferenceTechnicianApp
import com.karuhun.ftth.mobile.data.KtorFieldTransport
import com.karuhun.ftth.mobile.data.ReferenceTechnicianRepository
import com.karuhun.ftth.mobile.data.TechnicianAuthentication
import com.karuhun.ftth.mobile.domain.AccountSecureOutbox
import com.karuhun.ftth.mobile.storage.AndroidSecureOutbox
import com.karuhun.ftth.mobile.storage.AndroidTechnicianCredentials
import java.util.UUID

class TechnicianActivity : ComponentActivity() {
    private lateinit var photos: AndroidFieldPhotos
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val outbox = AccountSecureOutbox { AndroidSecureOutbox(applicationContext, it) }
        val auth = TechnicianAuthentication(KtorFieldTransport(), AndroidTechnicianCredentials(applicationContext),
            { UUID.randomUUID().toString() }, outbox::purge)
        val repository = ReferenceTechnicianRepository(auth, outbox, { UUID.randomUUID().toString() })
        photos = AndroidFieldPhotos(this)
        setContent { ReferenceTechnicianApp(repository, photos) }
    }
    override fun onDestroy() { photos.close(); super.onDestroy() }
}
